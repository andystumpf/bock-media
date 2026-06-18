package com.bockmedia.console.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.ui.alexaRemotePlayMessage
import com.bockmedia.console.ui.rememberAlexaRemoteStatus
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.library.LibraryArtPrefetch
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun HomeScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf(HomeFeedCache.peek()) }
    var loading by remember { mutableStateOf(HomeFeedCache.peek() == null) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HomeFilter.All) }
    var error by remember { mutableStateOf<String?>(null) }
    var offlineSection by remember { mutableStateOf<HomeSection?>(null) }
    var showAllSection by remember { mutableStateOf<HomeSection?>(null) }
    var actionCard by remember { mutableStateOf<HomeCard?>(null) }
    var warmJob by remember { mutableStateOf<Job?>(null) }
    val downloadStatuses by OfflineDownloadManager.statuses.collectAsState()
    val alexaStatus by rememberAlexaRemoteStatus(repository)
    val alexaBanner = alexaRemotePlayMessage(alexaStatus).takeIf { !remoteOk }

    suspend fun prefetchHomeArt(homeFeed: HomeFeed) {
        val base = repository.peekBaseUrl() ?: return
        val urls = homeFeed.sections.flatMap { it.cards }
            .mapNotNull { HomeArtworkResolver.peekUrl(base, it) }
        if (urls.isNotEmpty()) ArtworkPrefetch.prefetchUrls(context, urls)
    }

    fun warmArtwork(homeFeed: HomeFeed?, forceNetwork: Boolean = false) {
        val nonNullFeed = homeFeed ?: return
        val cards = nonNullFeed.sections.flatMap { it.cards }
        if (cards.isEmpty()) return
        warmJob?.cancel()
        val fullyCached = HomeArtworkCache.isFullyWarmed(cards)
        if (fullyCached && !forceNetwork) {
            warmJob = scope.launch { prefetchHomeArt(nonNullFeed) }
            return
        }
        warmJob = scope.launch {
            val urls = HomeArtworkResolver.warmAll(repository, cards)
            ArtworkPrefetch.prefetchUrls(context, urls)
            HomeCachePersistence.save(
                context,
                nonNullFeed,
                HomeArtworkCache.snapshotCardPaths(),
                HomeArtworkCache.snapshotPlaylistPaths(),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { warmJob?.cancel() }
    }

    suspend fun persistHomeCache(feed: HomeFeed) {
        val cards = feed.sections.flatMap { it.cards }
        if (cards.isEmpty()) return
        HomeCachePersistence.save(
            context,
            feed,
            HomeArtworkCache.snapshotCardPaths(),
            HomeArtworkCache.snapshotPlaylistPaths(),
        )
    }

    suspend fun loadOffline() {
        offlineSection = buildOfflineHomeSection(context)
    }

    suspend fun load() {
        if (feed == null) loading = true
        error = null
        try {
            runCatching {
                var fresh = withTimeout(60_000) { HomeFeedLoader.load(repository) }
                if (fresh.sections.isEmpty() && !repository.testConnection().isSuccess) {
                    BockMediaApp.get(context).invalidateEndpoint()
                    fresh = withTimeout(60_000) { HomeFeedLoader.load(repository) }
                }
                if (fresh.sections.isNotEmpty()) {
                    HomeFeedCache.put(fresh)
                    feed = fresh
                    HomeLoadCoordinator.markLoaded()
                    val cards = fresh.sections.flatMap { it.cards }
                    if (!HomeArtworkCache.isFullyWarmed(cards)) {
                        warmArtwork(fresh)
                    } else {
                        scope.launch { persistHomeCache(fresh) }
                    }
                } else if (feed == null) {
                    val reachable = runCatching { repository.testConnection().isSuccess }.getOrDefault(false)
                    error = if (!reachable) {
                        "Can't reach your Bock Media server (ports 3001 / 3005). " +
                            "Check the server is running at home and port forwarding is enabled."
                    } else {
                        "Could not load your library. Pull down to refresh or check server connection in Settings."
                    }
                }
            }.onFailure { error = it.message ?: "Could not load home" }
        } finally {
            loading = false
            refreshing = false
        }
    }

    fun warmLibraryInBackground() {
        if (LibrarySessionCache.getIfFresh() != null) return
        scope.launch {
            runCatching {
                val data = LibraryLoader.loadBuckets(repository, context)
                LibrarySessionCache.put(data)
                LibraryCachePersistence.save(context, data)
                LibraryArtPrefetch.warm(context, repository, data.forFilter(LibraryFilter.All))
            }
        }
    }

    fun warmOtherTabsInBackground() {
        if (AutomationSessionCache.getIfFresh() == null) {
            scope.launch {
                runCatching {
                    val loaded = repository.automations().items
                    val st = repository.alexaRemoteStatus()
                    AutomationSessionCache.put(loaded, st.configured && st.authenticated == true)
                }
            }
        }
        if (SearchBrowseSessionCache.getIfFresh() == null) {
            scope.launch {
                runCatching {
                    SearchBrowseSessionCache.put(SearchBrowseLoader.load(repository))
                }
            }
        }
    }

    suspend fun bootstrapHome() {
        HomeFeedCache.peek()?.let { cached ->
            feed = cached
            loading = false
            HomeLoadCoordinator.markLoaded()
            prefetchHomeArt(cached)
            if (!HomeArtworkCache.isFullyWarmed(cached.sections.flatMap { it.cards })) {
                warmArtwork(cached)
            }
        } ?: HomeCachePersistence.load(context)?.let { snap ->
            if (snap.feed.hasCurrentHomeLayout()) {
                HomeArtworkCache.restore(snap.cardMediaPaths, snap.playlistPaths)
                HomeFeedCache.put(snap.feed)
                feed = snap.feed
                loading = false
                HomeLoadCoordinator.markLoaded()
                prefetchHomeArt(snap.feed)
            }
        }
        scope.launch {
            runCatching { repository.primeBaseUrl(BockMediaApp.get(context).resolveBaseUrl()) }
        }
        if (!DeviceCatalog.isFresh()) {
            scope.launch { runCatching { DeviceCatalog.refresh(repository, probe = false) } }
        }
        scope.launch { OfflineDownloadManager.refresh(context) }
        if (HomeLoadCoordinator.shouldSkipReload()) {
            loadOffline()
            warmLibraryInBackground()
            warmOtherTabsInBackground()
            return
        }
        if (feed != null) {
            loadOffline()
            warmLibraryInBackground()
            warmOtherTabsInBackground()
            scope.launch {
                HomeLoadCoordinator.withLoadLock {
                    load()
                    loadOffline()
                }
            }
            return
        }
        HomeLoadCoordinator.withLoadLock {
            load()
            loadOffline()
        }
        warmLibraryInBackground()
        warmOtherTabsInBackground()
    }

    LaunchedEffect(Unit) {
        bootstrapHome()
    }

    LaunchedEffect(filter) {
        if (filter == HomeFilter.Offline) loadOffline()
    }

    val jumpBackIn = feed?.sections?.firstOrNull { it.kind == HomeSectionKind.JumpBackIn }
    val showShortcuts = filter == HomeFilter.All && jumpBackIn != null

    val sections = when (filter) {
        HomeFilter.Offline -> listOfNotNull(offlineSection)
        else -> feed?.sections.orEmpty()
            .filter { filter.matches(it.kind) }
            .filter { section ->
                !(showShortcuts && section.kind == HomeSectionKind.JumpBackIn)
            }
    }

    actionCard?.let { card ->
        HomeCardActionSheet(
            card = card,
            downloadState = downloadStatuses[card.playTarget.downloadId()]?.state,
            onDismiss = { actionCard = null },
            onPlay = {
                HomeTileEngagement.recordSelection(card.id)
                actionCard = null
                onPlay(card.playTarget)
            },
            onDownload = {
                actionCard = null
                OfflineDownloadManager.download(context, card.playTarget)
            },
        )
    }

    showAllSection?.let { section ->
        HomeSectionShowAllSheet(
            section = section,
            onDismiss = { showAllSection = null },
            onOpenPlaylist = { id ->
                showAllSection = null
                onOpenPlaylist(id)
            },
            onPlay = { card ->
                HomeTileEngagement.recordSelection(card.id)
                showAllSection = null
                onPlay(card.playTarget)
            },
        )
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            scope.launch {
                HomeLoadCoordinator.withLoadLock {
                    load()
                    loadOffline()
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            loading && feed == null -> LoadingBox()
            error != null && feed == null -> ErrorText(error!!) { scope.launch { load() } }
            else -> BockLazyColumn(Modifier.fillMaxSize().testTag(BockTestTags.HOME_FEED)) {
                item {
                    HomeHeader(
                        selected = filter,
                        onSelect = { filter = it },
                        onAccountNavigate = onAccountNavigate,
                    )
                }
                if (alexaBanner != null) {
                    item {
                        Text(
                            alexaBanner,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (showShortcuts) {
                    item {
                        HomeShortcutGrid(
                            cards = jumpBackIn!!.cards,
                            repository = repository,
                            onPlay = { card ->
                                HomeTileEngagement.recordSelection(card.id)
                                onPlay(card.playTarget)
                            },
                            onLongPress = { actionCard = it },
                            onOpenPlaylist = onOpenPlaylist,
                        )
                    }
                }
                if (sections.isEmpty() && !showShortcuts) {
                    item { HomeEmptyState(filter) }
                } else {
                    items(sections.size, key = { "${filter.name}-${sections[it].id}" }) { index ->
                        SpotifyHomeSection(
                            section = sections[index],
                            repository = repository,
                            onPlay = { card ->
                                HomeTileEngagement.recordSelection(card.id)
                                onPlay(card.playTarget)
                            },
                            onLongPress = { actionCard = it },
                            onOpenPlaylist = onOpenPlaylist,
                            onShowAll = { showAllSection = it },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun HomeEmptyState(filter: HomeFilter) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (filter == HomeFilter.Offline) "Nothing downloaded yet" else "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            if (filter == HomeFilter.Offline) {
                "Download playlists from Home tiles or long-press for more."
            } else {
                "Pull down to refresh, or search for music to play."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = BockMuted,
            textAlign = TextAlign.Center,
        )
    }
}
