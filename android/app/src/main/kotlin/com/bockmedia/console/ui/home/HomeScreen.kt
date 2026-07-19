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
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.FollowNotificationSync
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadSync
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.library.LibraryArtPrefetch
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
fun HomeScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenListenAgent: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onBrowseHomeCard: (HomeCard) -> Unit = {},
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
    val alexaStatus by rememberAlexaRemoteStatus(repository)
    val alexaBanner = alexaRemotePlayMessage(alexaStatus).takeIf { !remoteOk }
    var profileBanner by remember { mutableStateOf<String?>(null) }
    var profileFirstName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            val members = runCatching { repository.household().members }.getOrDefault(emptyList())
            if (members.isNotEmpty()) {
                profileBanner = "Select your profile in Family to restore ratings and settings."
            }
        }
    }

    suspend fun prefetchHomeArt(homeFeed: HomeFeed) {
        val base = repository.peekBaseUrl() ?: return
        val cards = HomeArtworkResolver.visibleCardsForWarm(homeFeed)
        val urls = cards.mapNotNull { HomeArtworkResolver.peekUrl(base, it) }
        if (urls.isNotEmpty()) ArtworkPrefetch.prefetchUrls(context, urls)
    }

    fun warmArtwork(homeFeed: HomeFeed?, forceNetwork: Boolean = false) {
        val nonNullFeed = homeFeed ?: return
        // Warm everything (IO-dispatched, concurrency-capped) — cold tiles otherwise
        // fire per-tile lookups while scrolling, which is what janks the feed.
        val cards = HomeArtworkResolver.visibleCardsForWarm(nonNullFeed)
        if (cards.isEmpty()) return
        warmJob?.cancel()
        val fullyCached = HomeArtworkCache.isFullyWarmed(cards)
        if (fullyCached && !forceNetwork) {
            warmJob = scope.launch(Dispatchers.IO) { prefetchHomeArt(nonNullFeed) }
            return
        }
        warmJob = scope.launch(Dispatchers.IO) {
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
        offlineSection = withContext(Dispatchers.IO) {
            buildOfflineHomeSection(context)
        }
    }

    suspend fun load(forcePaint: Boolean = false) {
        if (feed == null) loading = true
        error = null
        try {
            runCatching {
                var fresh = withTimeout(60_000) { HomeFeedLoader.load(context, repository) }
                if (fresh.sections.isEmpty() && !repository.testConnection().isSuccess) {
                    BockMediaApp.get(context).invalidateEndpoint()
                    fresh = withTimeout(60_000) { HomeFeedLoader.load(context, repository) }
                }
                if (fresh.sections.isNotEmpty()) {
                    val hadVisibleFeed = feed != null && !forcePaint
                    HomeFeedCache.put(fresh)
                    HomeLoadCoordinator.markLoaded()
                    val withinSkipWindow = HomeLoadCoordinator.shouldSkipReload()
                    if (HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed, withinSkipWindow)) {
                        feed = fresh
                        scope.launch { FollowNotificationSync.checkAndNotify(context, repository) }
                        val cards = fresh.sections.flatMap { it.cards }
                        if (!HomeArtworkCache.isFullyWarmed(cards)) {
                            warmArtwork(fresh)
                        } else {
                            scope.launch { persistHomeCache(fresh) }
                        }
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
        TabWarmCoordinator.warmLibrary(scope, context, repository)
    }

    fun warmOtherTabsInBackground() {
        TabWarmCoordinator.warmSearchBrowse(scope, repository)
    }

    suspend fun bootstrapHome() {
        val profileLinked = !ActiveProfileStore.activeMemberId(context).isNullOrBlank()
        HomeFeedCache.peek()?.let { cached ->
            if (!cached.isUsableHomeCache(profileLinked, HomeFeedCache.peekHasRatedSongs())) {
                HomeFeedCache.invalidate()
                return@let
            }
            feed = cached
            loading = false
            HomeLoadCoordinator.markLoaded()
            scope.launch { prefetchHomeArt(cached) }
            if (!HomeArtworkCache.isFullyWarmed(cached.sections.flatMap { it.cards })) {
                warmArtwork(cached)
            }
        } ?: HomeCachePersistence.load(context)?.let { snap ->
            if (snap.feed.isUsableHomeCache(profileLinked, snap.hasRatedSongs)) {
                HomeArtworkCache.restore(snap.cardMediaPaths, snap.playlistPaths)
                HomeFeedCache.put(snap.feed, hasRatedSongs = snap.hasRatedSongs)
                feed = snap.feed
                loading = false
                HomeLoadCoordinator.markLoaded()
                scope.launch { prefetchHomeArt(snap.feed) }
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { repository.primeBaseUrl(BockMediaApp.get(context).resolveBaseUrl()) }
        }
        if (!DeviceCatalog.isFresh()) {
            scope.launch(Dispatchers.IO) {
                runCatching { DeviceCatalog.refresh(repository, probe = false) }
            }
        }
        scope.launch(Dispatchers.IO) { OfflineDownloadManager.refresh(context) }

        fun scheduleReload() {
            scope.launch(Dispatchers.IO) {
                HomeLoadCoordinator.withLoadLock {
                    load()
                    loadOffline()
                }
            }
        }

        if (HomeLoadCoordinator.shouldSkipReload()) {
            scope.launch(Dispatchers.IO) { loadOffline() }
            return
        }
        if (feed != null) {
            scope.launch(Dispatchers.IO) { loadOffline() }
            scheduleReload()
            return
        }
        loading = true
        scheduleReload()
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) { bootstrapHome() }
    }

    val activeMemberId by ActiveProfileStore.activeMemberIdState.collectAsState()
    val profileRevision by ClientPrefsSync.profileChangeRevision.collectAsState()
    var previousMemberId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeMemberId, profileRevision) {
        scope.launch(Dispatchers.IO) {
            val id = activeMemberId?.trim()?.takeIf { it.isNotBlank() }
            profileFirstName = if (id == null) {
                null
            } else {
                val members = runCatching { repository.household().members }.getOrDefault(emptyList())
                members.find { it.id == id }?.name
                    ?.trim()
                    ?.substringBefore(' ')
                    ?.takeIf { it.isNotBlank() }
            }
            OfflineDownloadSync.claimOrphansForActiveProfile(context)
            loadOffline()
        }
    }

    LaunchedEffect(activeMemberId) {
        val prev = previousMemberId
        previousMemberId = activeMemberId
        if (!HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(prev, activeMemberId)) {
            return@LaunchedEffect
        }
        scope.launch(Dispatchers.IO) {
            HomeLoadCoordinator.withLoadLock { load(forcePaint = true) }
        }
    }

    LaunchedEffect(filter) {
        if (filter == HomeFilter.Offline) loadOffline()
    }

    val shortcutCards = remember(feed, filter) {
        feed?.homeShortcutCards().orEmpty().filter { it.eligibleForHomeShortcut() }
    }
    val showShortcuts = filter == HomeFilter.All && shortcutCards.isNotEmpty()

    val sections = remember(feed, filter, offlineSection, showShortcuts) {
        when (filter) {
            HomeFilter.Offline -> listOfNotNull(offlineSection)
            else -> feed?.sections.orEmpty()
                .filter { filter.matches(it.kind) }
                .filter { section ->
                    !(showShortcuts && section.kind == HomeSectionKind.JumpBackIn)
                }
        }
    }

    actionCard?.let { card ->
        HomeCardActionSheet(
            card = card,
            downloadState = OfflineDownloadManager.statusFor(context, card.playTarget)?.state,
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
            repository = repository,
            remoteOk = remoteOk,
            onDismiss = { showAllSection = null },
            onBrowse = { card ->
                showAllSection = null
                onBrowseHomeCard(card)
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
            scope.launch(Dispatchers.IO) {
                HomeLoadCoordinator.withLoadLock {
                    load(forcePaint = true)
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
                        onOpenListenAgent = onOpenListenAgent,
                        profileFirstName = profileFirstName,
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
                if (profileBanner != null) {
                    item {
                        Text(
                            profileBanner!!,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (showShortcuts) {
                    item {
                        HomeShortcutGrid(
                            cards = shortcutCards,
                            repository = repository,
                            onPlay = { card ->
                                HomeTileEngagement.recordSelection(card.id)
                                onPlay(card.playTarget)
                            },
                            onLongPress = { actionCard = it },
                        )
                    }
                }
                if (sections.isEmpty() && !showShortcuts) {
                    item { HomeEmptyState(filter) }
                } else {
                    items(
                        count = sections.size,
                        key = { "${filter.name}-${sections[it].id}" },
                        contentType = { "home_section" },
                    ) { index ->
                        SpotifyHomeSection(
                            section = sections[index],
                            repository = repository,
                            remoteOk = remoteOk,
                            onPlay = { card ->
                                HomeTileEngagement.recordSelection(card.id)
                                onPlay(card.playTarget)
                            },
                            onLongPress = { actionCard = it },
                            onBrowse = onBrowseHomeCard,
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
                "Download playlists from Home tiles or open them in Your Library."
            } else {
                "Pull down to refresh, or search for music to play."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = BockMuted,
            textAlign = TextAlign.Center,
        )
    }
}
