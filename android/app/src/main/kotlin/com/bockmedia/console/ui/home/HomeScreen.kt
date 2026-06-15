package com.bockmedia.console.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenDownloads: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feed by remember { mutableStateOf<HomeFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(HomeFilter.All) }
    var error by remember { mutableStateOf<String?>(null) }
    var offlineSection by remember { mutableStateOf<HomeSection?>(null) }
    var showAllSection by remember { mutableStateOf<HomeSection?>(null) }
    var actionCard by remember { mutableStateOf<HomeCard?>(null) }
    var artworkEpoch by remember { mutableIntStateOf(0) }
    val downloadStatuses by OfflineDownloadManager.statuses.collectAsState()
    val alexaStatus by rememberAlexaRemoteStatus(repository)
    val alexaBanner = alexaRemotePlayMessage(alexaStatus).takeIf { !remoteOk }

    fun warmArtwork(homeFeed: HomeFeed?) {
        val cards = homeFeed?.sections.orEmpty().flatMap { it.cards } ?: return
        artworkEpoch++
        scope.launch {
            HomeArtworkResolver.warmPlaylistCovers(repository, cards)
            artworkEpoch++
        }
    }

    suspend fun loadOffline() {
        offlineSection = buildOfflineHomeSection(context)
    }

    suspend fun load() {
        if (feed == null) loading = true
        error = null
        runCatching {
            var fresh = HomeFeedLoader.load(repository)
            if (fresh.sections.isEmpty() && !repository.testConnection().isSuccess) {
                BockMediaApp.get(context).invalidateApi()
                fresh = HomeFeedLoader.load(repository)
            }
            if (fresh.sections.isNotEmpty()) {
                HomeFeedCache.put(fresh)
                feed = fresh
                warmArtwork(fresh)
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
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) {
        OfflineDownloadManager.refresh(context)
        HomeFeedCache.getIfFresh()?.let { cached ->
            feed = cached
            loading = false
            artworkEpoch++
        }
        load()
        loadOffline()
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
            onPlay = { card ->
                HomeTileEngagement.recordSelection(card.id)
                showAllSection = null
                onPlay(card.playTarget)
            },
        )
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load(); loadOffline() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            loading && feed == null -> LoadingBox()
            error != null && feed == null -> ErrorText(error!!) { scope.launch { load() } }
            else -> BockLazyColumn(Modifier.fillMaxSize()) {
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
                            artworkEpoch = artworkEpoch,
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
                    items(sections.size, key = { "${filter.name}-${sections[it].id}" }) { index ->
                        SpotifyHomeSection(
                            section = sections[index],
                            repository = repository,
                            artworkEpoch = artworkEpoch,
                            onPlay = { card ->
                                HomeTileEngagement.recordSelection(card.id)
                                onPlay(card.playTarget)
                            },
                            onLongPress = { actionCard = it },
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
