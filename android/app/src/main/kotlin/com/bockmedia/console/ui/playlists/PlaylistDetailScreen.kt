package com.bockmedia.console.ui.playlists

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.ui.downloads.rememberVisibleDownloadStatuses
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.media.LOCAL_PHONE_DEVICE_ID
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.discovery.AcquireIdeasDialog
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    repository: BockMediaRepository,
    playlistId: String,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
    onLocalPlayStarted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("title") }
    var sortOrder by remember { mutableStateOf("asc") }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showRename by remember { mutableStateOf(false) }
    var showMixMuse by remember { mutableStateOf(false) }
    var mixMuseSeed by remember { mutableStateOf<DiscoverySeed?>(null) }
    var discoverySeed by remember { mutableStateOf<DiscoverySeed?>(null) }
    var acquireIdeasSeed by remember { mutableStateOf<DiscoverySeed?>(null) }
    var reorderMode by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    var playlistSource by remember { mutableStateOf<String?>(null) }
    var playlistSourceName by remember { mutableStateOf<String?>(null) }
    var isDaily by remember { mutableStateOf(false) }
    var ownerMemberId by remember { mutableStateOf<String?>(null) }
    var ownerName by remember { mutableStateOf<String?>(null) }
    var visibility by remember { mutableStateOf<String?>(null) }
    var sharedWith by remember { mutableStateOf<List<String>>(emptyList()) }
    var showShare by remember { mutableStateOf(false) }
    var householdMembers by remember { mutableStateOf(emptyList<com.bockmedia.console.data.api.dto.HouseholdMember>()) }
    var addToRoom by remember { mutableStateOf<Triple<String, String, String?>?>(null) }
    var reorderError by remember { mutableStateOf<String?>(null) }
    var trackMenu by remember { mutableStateOf<PlaylistTrack?>(null) }
    var showPlaylistMenu by remember { mutableStateOf(false) }
    val canReorder = filter.isBlank() && sortBy == "title" && sortOrder == "asc" &&
        isPlaylistEditable(playlistSource, playlistSourceName)
    val listState = rememberLazyListState()
    val pageSize = 100
    val playTarget = remember(name, playlistId) { PlayTarget.Playlist(playlistId, name) }
    val downloadStatus = rememberVisibleDownloadStatuses()
    val collectionStatus = downloadStatus[playTarget.downloadId()]
    val downloaded = collectionStatus?.state == DownloadState.Complete
    val downloading = collectionStatus?.state == DownloadState.Downloading
    val downloadFailed = collectionStatus?.state == DownloadState.Failed
    val downloadProgress = collectionStatus?.progress ?: 0f

    val artUrl by produceState<String?>(null, playlistId) {
        value = repository.artworkUrlForPlaylist(playlistId)
    }
    val totalDurationSec = tracks.sumOf { it.duration ?: 0 }

    ImmersiveDarkStatusBar()

    suspend fun loadPage(page: Int, append: Boolean) {
        if (append) {
            if (loadingMore) return
            loadingMore = true
        } else {
            loading = tracks.isEmpty()
            loadError = null
        }
        runCatching {
            val d = repository.playlistDetail(
                playlistId,
                page = page,
                limit = pageSize,
                q = filter.ifBlank { null },
                sortBy = sortBy,
                order = sortOrder,
            )
            name = d.name
            editName = d.name
            playlistSource = d.source
            playlistSourceName = d.sourceName
            isDaily = d.daily
            ownerMemberId = d.ownerMemberId
            ownerName = d.ownerName
            visibility = d.visibility
            sharedWith = d.sharedWith
            total = d.total.takeIf { it > 0 } ?: d.tracks.size
            tracks = if (append) {
                val seen = tracks.mapNotNull { it.path }.toMutableSet()
                tracks + d.tracks.filter { track ->
                    val path = track.path
                    path == null || seen.add(path)
                }
            } else {
                d.tracks
            }
        }.onFailure {
            if (!append) loadError = httpErrorMessage(it, "Could not load playlist")
        }
        loading = false
        loadingMore = false
        refreshing = false
    }

    val reloadKey = remember(playlistId, sortBy, sortOrder, filter) {
        listOf(playlistId, sortBy, sortOrder, filter)
    }
    LaunchedEffect(reloadKey) {
        if (filter.isNotEmpty()) delay(400)
        loadPage(page = 1, append = false)
    }

    suspend fun applySort(newSortBy: String? = null, newOrder: String? = null) {
        newSortBy?.let { sortBy = it }
        newOrder?.let { sortOrder = it }
        reorderMode = false
        if (filter.isBlank()) {
            runCatching { repository.sortPlaylist(playlistId, sortBy, sortOrder) }
        }
    }

    suspend fun loadAllTracksIfNeeded() {
        if (tracks.size >= total) return
        val all = mutableListOf<PlaylistTrack>()
        var page = 1
        while (true) {
            val d = repository.playlistDetail(playlistId, page = page, limit = pageSize, sortBy = sortBy, order = sortOrder)
            if (page == 1) {
                name = d.name
                playlistSource = d.source
                playlistSourceName = d.sourceName
                total = d.total.takeIf { it > 0 } ?: d.tracks.size
            }
            all.addAll(d.tracks)
            if (d.tracks.size < pageSize || all.size >= total) break
            page++
        }
        tracks = all
    }

    suspend fun moveTrack(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in tracks.indices || toIndex !in tracks.indices || fromIndex == toIndex) return
        val path = tracks[fromIndex].path ?: return
        if (tracks.size < total) loadAllTracksIfNeeded()
        val previous = tracks
        val reordered = tracks.toMutableList()
        val item = reordered.removeAt(fromIndex)
        reordered.add(toIndex.coerceIn(0, reordered.size), item)
        tracks = reordered
        reorderError = null
        runCatching { repository.movePlaylistTrack(playlistId, path, toIndex) }
            .onFailure {
                tracks = previous
                reorderError = httpErrorMessage(it, "Could not reorder track")
            }
    }

    LaunchedEffect(listState, tracks.size, total, loading, loadingMore, reloadKey) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, itemCount) ->
            val hasMore = tracks.isNotEmpty() && tracks.size < total
            if (!loading && !loadingMore && hasMore && itemCount > 0 && lastVisible >= itemCount - 5) {
                loadPage(page = (tracks.size / pageSize) + 1, append = true)
            }
        }
    }

    if (showShare) {
        SharePlaylistDialog(
            members = householdMembers,
            activeMemberId = ActiveProfileStore.activeMemberId(context),
            alreadyShared = sharedWith.toSet(),
            onDismiss = { showShare = false },
            onShare = { ids ->
                scope.launch {
                    runCatching { repository.sharePlaylist(playlistId, ids) }
                        .onSuccess {
                            sharedWith = ids.sorted()
                            visibility = "shared"
                            showShare = false
                            android.widget.Toast.makeText(
                                context,
                                "Playlist shared",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .onFailure { e ->
                            android.widget.Toast.makeText(
                                context,
                                httpErrorMessage(e, "Could not share playlist"),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                }
            },
        )
    }

    addToRoom?.let { (path, title, artist) ->
        AddToRoomSheet(
            repository = repository,
            path = path,
            track = title,
            artist = artist,
            remoteOk = remoteOk,
            onDismiss = { addToRoom = null },
            onSuccess = { msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                addToRoom = null
            },
            onError = { e ->
                android.widget.Toast.makeText(context, e, android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = { BockTextField(editName, { editName = it }, "Name") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repository.renamePlaylist(playlistId, editName.trim()) }
                            .onSuccess {
                                showRename = false
                                loadPage(page = 1, append = false)
                            }
                            .onFailure { e ->
                                showRename = false
                                android.widget.Toast.makeText(
                                    context,
                                    httpErrorMessage(e, "Could not rename playlist"),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    mixMuseSeed?.let { seed ->
        if (showMixMuse) {
            MixMuseDialog(
                repository = repository,
                seed = seed,
                onDismiss = { showMixMuse = false; mixMuseSeed = null },
                onPlaylistCreated = { id, plName -> onPlay(PlayTarget.Playlist(id, plName)) },
            )
        }
    }
    discoverySeed?.let { seed ->
        DiscoveryActionsDialog(
            seed = seed,
            onDismiss = { discoverySeed = null },
            onMixMuse = { mixMuseSeed = seed; showMixMuse = true },
            onResonanceRadio = { scope.launch { repository.runResonanceRadio(context, seed) } },
            onResonanceMix = {
                scope.launch {
                    repository.runResonanceMix(seed) { id, plName -> onPlay(PlayTarget.Playlist(id, plName)) }
                }
            },
            onAcquireIdeas = { acquireIdeasSeed = seed },
        )
    }
    acquireIdeasSeed?.let { seed ->
        AcquireIdeasDialog(
            repository = repository,
            seed = seed,
            onDismiss = { acquireIdeasSeed = null },
        )
    }

    Box(Modifier.fillMaxSize()) {
        ArtBackdrop(artUrl = artUrl)
        Column(Modifier.fillMaxSize()) {
            PlaylistInlineTopBar(title = name.ifBlank { "Playlist" }, onBack = onBack)
            when {
                loading && tracks.isEmpty() -> LoadingBox(Modifier.weight(1f))
                loadError != null && tracks.isEmpty() -> ErrorText(loadError!!) {
                    scope.launch { loadPage(1, append = false) }
                }
                else -> {
                    BockPullRefresh(
                        isRefreshing = refreshing,
                        onRefresh = { refreshing = true; scope.launch { loadPage(1, append = false) } },
                        modifier = Modifier.weight(1f),
                    ) {
                        BockLazyColumn(Modifier.fillMaxSize(), state = listState) {
                            item(key = "hero") {
                                PlexampPlaylistHero(
                                    repository = repository,
                                    playlistId = playlistId,
                                    name = name,
                                    tracks = tracks,
                                    artUrl = artUrl,
                                    onPlay = { onPlay(playTarget) },
                                    onShuffle = { onPlay(PlayTarget.Playlist(playlistId, name, shuffle = true)) },
                                    onDownload = {
                                        if (downloaded) OfflineDownloadManager.resync(context, playTarget)
                                        else OfflineDownloadManager.downloadPlaylist(context, playlistId, name)
                                    },
                                    onRename = { showRename = true },
                                    onDelete = {
                                        scope.launch {
                                            runCatching { repository.deletePlaylist(playlistId) }
                                                .onSuccess { onBack() }
                                                .onFailure { e ->
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        httpErrorMessage(e, "Could not delete playlist"),
                                                        android.widget.Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                        }
                                    },
                                    onDiscover = {
                                        discoverySeed = DiscoverySeed(
                                            kind = DiscoverySeedKind.playlist,
                                            title = name,
                                            playlistId = playlistId,
                                        )
                                    },
                                    isDaily = isDaily,
                                    onSaveDaily = {
                                        scope.launch {
                                            runCatching { repository.saveDailyPlaylist(playlistId) }
                                                .onSuccess {
                                                    isDaily = false
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Saved to your library",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                    loadPage(page = 1, append = false)
                                                }
                                                .onFailure { e ->
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        httpErrorMessage(e, "Could not save playlist"),
                                                        android.widget.Toast.LENGTH_LONG,
                                                    ).show()
                                                }
                                        }
                                    },
                                    downloaded = downloaded,
                                    downloading = downloading,
                                    onToggleFilters = { showFilters = !showFilters },
                                    canReorder = canReorder,
                                    reorderMode = reorderMode,
                                    onToggleReorder = {
                                        reorderError = null
                                        reorderMode = !reorderMode
                                        if (reorderMode) scope.launch { loadAllTracksIfNeeded() }
                                    },
                                    onOpenMenu = { showPlaylistMenu = true },
                                )
                            }
                            item(key = "summary") {
                                PlaylistTrackSummaryBar(
                                    trackCount = total,
                                    totalSeconds = totalDurationSec,
                                )
                            }
                            if (showFilters) {
                                item(key = "filters") {
                                    PlaylistFilterBar(
                                        filter = filter,
                                        onFilterChange = { filter = it; reorderMode = false },
                                        sortBy = sortBy,
                                        sortOrder = sortOrder,
                                        onSort = { by, order -> scope.launch { applySort(by, order) } },
                                        reorderError = reorderError,
                                    )
                                }
                            }
                            if (downloading) {
                                item(key = "download-progress") {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .height(3.dp),
                                        color = BockGreen,
                                    )
                                }
                            }
                            items(tracks.size, key = { idx -> "${tracks[idx].path ?: "row"}-$idx" }) { idx ->
                                val t = tracks[idx]
                                PlexampPlaylistTrackRow(
                                    repository = repository,
                                    track = t,
                                    reorderMode = reorderMode,
                                    canMoveUp = reorderMode && idx > 0,
                                    canMoveDown = reorderMode && idx < tracks.lastIndex,
                                    onMoveUp = { scope.launch { moveTrack(idx, idx - 1) } },
                                    onMoveDown = { scope.launch { moveTrack(idx, idx + 1) } },
                                    onClick = {
                                        scope.launch {
                                            if (tracks.size < total) loadAllTracksIfNeeded()
                                            val queue = tracks.mapNotNull { pt ->
                                                pt.path?.let { path ->
                                                    LocalTrack(
                                                        path = path,
                                                        title = pt.title ?: path,
                                                        artist = pt.artist,
                                                        album = pt.album,
                                                        durationMs = (pt.duration ?: 0).coerceAtLeast(0) * 1000L,
                                                    )
                                                }
                                            }
                                            if (queue.isEmpty()) return@launch
                                            val startIdx = queue.indexOfFirst { it.path == t.path }
                                                .takeIf { it >= 0 } ?: idx
                                            LocalPlaybackController.playPlaylistFromIndex(
                                                context,
                                                playTarget,
                                                queue,
                                                startIdx,
                                            )
                                            val err = LocalPlaybackController.state.value.error
                                            if (err != null) {
                                                android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                            } else {
                                                PlaybackFocus.notePlayStarted(LOCAL_PHONE_DEVICE_ID, "This phone")
                                                onLocalPlayStarted()
                                            }
                                        }
                                    },
                                    onMenu = { trackMenu = t },
                                )
                            }
                            if (loadingMore) {
                                item(key = "loading-more") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        BockProgressIndicator(size = 24.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    trackMenu?.let { track ->
        val path = track.path
        if (path != null) {
            PlexampEntitySheet(
                title = track.title ?: "Track",
                rating = RatingTarget(
                    kind = RatingKind.Song,
                    id = path,
                    title = track.title ?: "",
                    artist = track.artist,
                    album = track.album,
                ),
                repository = repository,
                actions = buildList {
                    add(PlexampSheetAction("Play", Icons.Default.PlayArrow, onClick = {
                        onPlay(PlayTarget.Song(path, track.title ?: "Track"))
                    }))
                    add(PlexampSheetAction("Discover similar", Icons.Default.AutoAwesome, onClick = {
                        discoverySeed = DiscoverySeed(
                            kind = DiscoverySeedKind.song,
                            title = track.title ?: path,
                            path = path,
                            artist = track.artist,
                            album = track.album,
                        )
                    }))
                    if (isPlaylistEditable(playlistSource, playlistSourceName)) {
                        add(PlexampSheetAction("Remove from playlist", Icons.Default.Delete, onClick = {
                            scope.launch {
                                repository.removePlaylistTrack(playlistId, path)
                                loadPage(page = 1, append = false)
                            }
                        }))
                    }
                    if (remoteOk) {
                        add(PlexampSheetAction("Add to room", Icons.Default.Add, onClick = {
                            addToRoom = Triple(path, track.title ?: "Track", track.artist)
                            trackMenu = null
                        }))
                    }
                },
                onDismiss = { trackMenu = null },
            )
        }
    }

    if (showPlaylistMenu) {
        PlexampEntitySheet(
            title = name.ifBlank { "Playlist" },
            rating = RatingTarget(
                kind = RatingKind.Playlist,
                id = playlistId,
                title = name,
            ),
            repository = repository,
            actions = buildList {
                if (isDaily) {
                    add(PlexampSheetAction("Save to your library", Icons.Default.Add, onClick = {
                        scope.launch {
                            runCatching { repository.saveDailyPlaylist(playlistId) }
                                .onSuccess {
                                    isDaily = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "Saved to your library",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                    loadPage(page = 1, append = false)
                                }
                                .onFailure { e ->
                                    android.widget.Toast.makeText(
                                        context,
                                        httpErrorMessage(e, "Could not save playlist"),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }))
                }
                add(PlexampSheetAction("Play", Icons.Default.PlayArrow, onClick = { onPlay(playTarget) }))
                add(PlexampSheetAction("Shuffle", Icons.Default.Shuffle, onClick = {
                    onPlay(PlayTarget.Playlist(playlistId, name, shuffle = true))
                }))
                add(
                    PlexampSheetAction(
                        label = if (downloaded) "Sync offline" else "Download for offline",
                        icon = Icons.Default.Download,
                        enabled = !downloading,
                        onClick = {
                            if (downloaded) OfflineDownloadManager.resync(context, playTarget)
                            else OfflineDownloadManager.downloadPlaylist(context, playlistId, name)
                        },
                    ),
                )
                add(PlexampSheetAction("Search & sort", Icons.Default.Tune, onClick = { showFilters = !showFilters }))
                if (canReorder) {
                    add(
                        PlexampSheetAction(
                            label = if (reorderMode) "Done reordering" else "Reorder tracks",
                            icon = Icons.Default.SwapVert,
                            onClick = {
                                reorderError = null
                                reorderMode = !reorderMode
                                if (reorderMode) scope.launch { loadAllTracksIfNeeded() }
                            },
                        ),
                    )
                }
                add(
                    PlexampSheetAction("Mix Muse / Resonance", Icons.Default.AutoAwesome, onClick = {
                        discoverySeed = DiscoverySeed(
                            kind = DiscoverySeedKind.playlist,
                            title = name,
                            playlistId = playlistId,
                        )
                    }),
                )
                add(PlexampSheetAction("Rename", Icons.Default.Edit, onClick = { showRename = true }))
                if (!isDaily) {
                    add(PlexampSheetAction("Share with…", Icons.Default.Share, onClick = {
                        scope.launch {
                            householdMembers = runCatching { repository.household().members }
                                .getOrDefault(emptyList())
                            showShare = true
                        }
                    }))
                }
                add(
                    PlexampSheetAction("Delete playlist", Icons.Default.Delete, onClick = {
                        scope.launch {
                            runCatching { repository.deletePlaylist(playlistId) }
                                .onSuccess { onBack() }
                                .onFailure { e ->
                                    android.widget.Toast.makeText(
                                        context,
                                        httpErrorMessage(e, "Could not delete playlist"),
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    }),
                )
            },
            onDismiss = { showPlaylistMenu = false },
        )
    }
}

@Composable
private fun PlexampPlaylistHero(
    repository: BockMediaRepository,
    playlistId: String,
    name: String,
    tracks: List<PlaylistTrack>,
    artUrl: String?,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDiscover: () -> Unit,
    isDaily: Boolean,
    onSaveDaily: () -> Unit,
    downloaded: Boolean,
    downloading: Boolean,
    onToggleFilters: () -> Unit,
    canReorder: Boolean,
    reorderMode: Boolean,
    onToggleReorder: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        com.bockmedia.console.ui.components.PlaylistCollageArt(
            repository = repository,
            title = name,
            artPaths = tracks.mapNotNull { it.path },
            fallbackArtUrl = artUrl,
            modifier = Modifier.size(120.dp),
            fallbackFontSize = 28.sp,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height(52.dp))
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play playlist", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = Color.White)
                }
                IconButton(onClick = onOpenMenu) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PlaylistTrackSummaryBar(trackCount: Int, totalSeconds: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (trackCount == 1) "1 TRACK" else "$trackCount TRACKS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            color = Color.White,
        )
        Spacer(Modifier.weight(1f))
        if (totalSeconds > 0) {
            Text(
                formatAlbumDuration(totalSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = Color.White.copy(alpha = 0.12f),
    )
}

@Composable
private fun PlaylistFilterBar(
    filter: String,
    onFilterChange: (String) -> Unit,
    sortBy: String,
    sortOrder: String,
    onSort: (String?, String?) -> Unit,
    reorderError: String?,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(filter, onFilterChange, "Search in playlist")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sortBy == "title", onClick = { onSort("title", null) }, label = { Text("Title") })
            FilterChip(selected = sortBy == "artist", onClick = { onSort("artist", null) }, label = { Text("Artist") })
            FilterChip(selected = sortBy == "album", onClick = { onSort("album", null) }, label = { Text("Album") })
            FilterChip(selected = sortOrder == "asc", onClick = { onSort(null, "asc") }, label = { Text("↑") })
            FilterChip(selected = sortOrder == "desc", onClick = { onSort(null, "desc") }, label = { Text("↓") })
        }
        reorderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlexampPlaylistTrackRow(
    repository: BockMediaRepository,
    track: PlaylistTrack,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    reorderMode: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = track.title ?: "",
        artPath = track.path,
        artistName = track.artist,
        albumName = track.album,
        variantKey = track.path ?: track.title.orEmpty(),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, enabled = track.path != null)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BockArtwork(
            model = artUrl,
            title = track.title ?: "",
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp)),
            shape = RoundedCornerShape(4.dp),
            fallbackFontSize = 14.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                Text(
                    artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reorderMode) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up", tint = Color.White)
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down", tint = Color.White)
            }
        }
        IconButton(onClick = onMenu) {
            Icon(Icons.Default.MoreVert, contentDescription = "Track options", tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun PlaylistInlineTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun ImmersiveDarkStatusBar() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val wasLight = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = wasLight }
    }
}

internal fun isPlaylistEditable(source: String?, sourceName: String?): Boolean {
    if (sourceName.equals("bockmedia", ignoreCase = true)) return true
    return !source.isNullOrBlank()
}
