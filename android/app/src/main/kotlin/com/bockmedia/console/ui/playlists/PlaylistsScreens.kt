package com.bockmedia.console.ui.playlists

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.ui.components.BockProgressIndicator
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.api.dto.SmartPlaylist
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.media.LOCAL_PHONE_DEVICE_ID
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.discovery.AcquireIdeasDialog
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SpotifyMuted = Color(0xFFB3B3B3)

@Composable
fun PlaylistsScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var smart by remember { mutableStateOf<List<SmartPlaylist>>(emptyList()) }
    var selectedMerge by remember { mutableStateOf(setOf<String>()) }
    var mergeMode by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showSmart by remember { mutableStateOf(false) }
    var editSmart by remember { mutableStateOf<SmartPlaylist?>(null) }
    var showMerge by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    val pageSize = 100

    suspend fun load() {
        runCatching {
            val loaded = repository.playlists(search).items
            // Batch-resolve cover paths once (one request) before rows render, so each
            // SpotifyPlaylistRow reads its cover from cache instead of firing its own
            // /cover call (perf #3 — fixes N+1 cover fetch).
            repository.prefetchPlaylistCoverPaths(loaded.map { it.id })
            playlists = loaded
            smart = repository.smartPlaylists().items
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(search) {
        delay(350)
        load()
        page = 1
    }

    val paged = playlists.drop((page - 1) * pageSize).take(pageSize)
    val totalPages = ((playlists.size + pageSize - 1) / pageSize).coerceAtLeast(1)

    if (showNew) NewPlaylistDialog(
        onDismiss = { showNew = false },
        onCreate = { name ->
            scope.launch { repository.createPlaylist(name); load(); showNew = false }
        },
    )
    if (showSmart) SmartPlaylistDialog(
        onDismiss = { showSmart = false },
        onCreate = { name, genre, artist, max, _ ->
            scope.launch { repository.createSmartPlaylist(name, genre, artist, max); load(); showSmart = false }
        },
    )
    editSmart?.let { sp ->
        SmartPlaylistDialog(
            initial = sp,
            onDismiss = { editSmart = null },
            onCreate = { name, genre, artist, max, enabled ->
                scope.launch {
                    repository.updateSmartPlaylist(sp.id, name, genre, artist, max, enabled)
                    load()
                    editSmart = null
                }
            },
        )
    }
    if (showMerge) MergeDialog(
        onDismiss = { showMerge = false },
        onMerge = { name ->
            scope.launch {
                repository.mergePlaylists(selectedMerge.toList(), name.ifBlank { null })
                selectedMerge = emptySet()
                mergeMode = false
                load()
                showMerge = false
            }
        },
    )
    if (showAi) AiPlaylistDialog(
        repository = repository,
        onDismiss = { showAi = false; scope.launch { load() } },
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchField(search, { search = it }, "Search playlists")
            Spacer(Modifier.height(8.dp))
            SearchActionRow {
                IconButton(onClick = { showNew = true }) { Icon(Icons.Default.Add, "New") }
                IconButton(onClick = { showSmart = true }) { Icon(Icons.Default.AutoAwesome, "Smart") }
                IconButton(onClick = { showAi = true }) { Icon(Icons.Default.Psychology, "Mix Muse") }
                IconButton(
                    onClick = {
                        if (mergeMode && selectedMerge.size >= 2) {
                            showMerge = true
                        } else {
                            mergeMode = !mergeMode
                            if (!mergeMode) selectedMerge = emptySet()
                        }
                    },
                    enabled = mergeMode && selectedMerge.size >= 2 || playlists.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.Merge,
                        "Merge",
                        tint = if (mergeMode) BockGreen else LocalContentColor.current,
                    )
                }
            }
            if (mergeMode) {
                Text(
                    "${selectedMerge.size} selected — pick 2+ then tap Merge",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        if (smart.isNotEmpty()) {
            Text(
                "Smart playlists",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            smart.forEach { sp ->
                ListItem(
                    headlineContent = { Text(sp.name) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { editSmart = sp }) { Text("Edit") }
                            TextButton(onClick = { scope.launch { repository.refreshSmartPlaylist(sp.id); load() } }) {
                                Text("Refresh")
                            }
                            TextButton(onClick = { scope.launch { repository.deleteSmartPlaylist(sp.id); load() } }) {
                                Text("Delete")
                            }
                        }
                    },
                )
            }
        }

        BockLazyColumn(Modifier.weight(1f)) {
            items(paged, key = { it.id }) { pl ->
                SpotifyPlaylistRow(
                    repository = repository,
                    playlist = pl,
                    mergeMode = mergeMode,
                    selected = pl.id in selectedMerge,
                    onSelectChange = { checked ->
                        selectedMerge = if (checked) selectedMerge + pl.id else selectedMerge - pl.id
                    },
                    onClick = {
                        if (mergeMode) {
                            selectedMerge = if (pl.id in selectedMerge) selectedMerge - pl.id else selectedMerge + pl.id
                        } else {
                            onOpenDetail(pl.id)
                        }
                    },
                )
            }
        }
        PaginationBar(page, totalPages) { page = it }
    }
}

@Composable
private fun SpotifyPlaylistRow(
    repository: BockMediaRepository,
    playlist: PlaylistSummary,
    mergeMode: Boolean,
    selected: Boolean,
    onSelectChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val artUrl by produceState<String?>(initialValue = null, playlist.id) {
        value = repository.artworkUrlForPlaylist(playlist.id)
    }
    val trackLabel = if (playlist.tracks == 1) "1 song" else "${playlist.tracks} songs"
    val sourceLabel = playlist.sourceName ?: playlist.source

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mergeMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectChange,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        BockArtwork(
            model = artUrl,
            title = playlist.name,
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(6.dp),
            fallbackFontSize = 20.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(trackLabel)
                    if (!sourceLabel.isNullOrBlank()) append(" · $sourceLabel")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
    var playlistSource by remember { mutableStateOf<String?>(null) }
    var playlistSourceName by remember { mutableStateOf<String?>(null) }
    var reorderError by remember { mutableStateOf<String?>(null) }
    val canReorder = filter.isBlank() && sortBy == "title" && sortOrder == "asc" &&
        isPlaylistEditable(playlistSource, playlistSourceName)
    val listState = rememberLazyListState()
    val pageSize = 100
    val playTarget = remember(name, playlistId) { PlayTarget.Playlist(playlistId, name) }
    val downloadStatus by OfflineDownloadManager.statuses.collectAsState()
    val collectionStatus = downloadStatus[playTarget.downloadId()]
    val downloaded = collectionStatus?.state == DownloadState.Complete
    val downloading = collectionStatus?.state == DownloadState.Downloading
    val downloadFailed = collectionStatus?.state == DownloadState.Failed
    val downloadProgress = collectionStatus?.progress ?: 0f

    suspend fun playOnPhone(shuffle: Boolean = false) {
        LocalPlaybackController.playTarget(context, playTarget, shuffle)
        val err = LocalPlaybackController.state.value.error
        if (err != null) {
            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
        } else {
            PlaybackFocus.notePlayStarted(LOCAL_PHONE_DEVICE_ID, "This phone")
            onLocalPlayStarted()
        }
    }

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
            onResonanceRadio = {
                scope.launch {
                    repository.runResonanceRadio(context, seed)
                }
            },
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

    Column(Modifier.fillMaxSize()) {
        when {
            loading && tracks.isEmpty() -> LoadingBox(Modifier.weight(1f))
            loadError != null && tracks.isEmpty() -> ErrorText(loadError!!) { scope.launch { loadPage(1, append = false) } }
            else -> {
            BockPullRefresh(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; scope.launch { loadPage(1, append = false) } },
                modifier = Modifier.weight(1f),
            ) {
                BockLazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item {
                        SpotifyPlaylistHeader(
                            repository = repository,
                            playlistId = playlistId,
                            name = name,
                            totalTracks = total,
                            totalDurationSec = tracks.sumOf { it.duration ?: 0 },
                            downloaded = downloaded,
                            downloading = downloading,
                            downloadFailed = downloadFailed,
                            downloadProgress = downloadProgress,
                            onPlay = { scope.launch { playOnPhone() } },
                            onPlayOnSpeaker = { onPlay(playTarget) },
                            onDownload = {
                                if (downloaded) {
                                    OfflineDownloadManager.resync(context, playTarget)
                                } else {
                                    OfflineDownloadManager.downloadPlaylist(context, playlistId, name)
                                }
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
                        )
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SearchField(filter, {
                                filter = it
                                reorderMode = false
                            }, "Search in playlist")
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SortChip("Title", sortBy == "title") { scope.launch { applySort(newSortBy = "title") } }
                                    SortChip("Artist", sortBy == "artist") { scope.launch { applySort(newSortBy = "artist") } }
                                    SortChip("Album", sortBy == "album") { scope.launch { applySort(newSortBy = "album") } }
                                    SortChip("↑", sortOrder == "asc") { scope.launch { applySort(newOrder = "asc") } }
                                    SortChip("↓", sortOrder == "desc") { scope.launch { applySort(newOrder = "desc") } }
                                }
                                if (canReorder) {
                                    TextButton(onClick = {
                                        reorderError = null
                                        reorderMode = !reorderMode
                                        if (reorderMode) scope.launch { loadAllTracksIfNeeded() }
                                    }) {
                                        Text(if (reorderMode) "Done" else "Reorder")
                                    }
                                }
                            }
                            reorderError?.let { msg ->
                                Text(
                                    msg,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    items(tracks.size, key = { idx -> "${tracks[idx].path ?: "row"}-$idx" }) { idx ->
                        val t = tracks[idx]
                        val trackNum = idx + 1
                        SpotifyTrackRow(
                            trackNum = trackNum,
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
                            onRemove = {
                                scope.launch {
                                    t.path?.let { repository.removePlaylistTrack(playlistId, it) }
                                    loadPage(page = 1, append = false)
                                }
                            },
                            onDiscover = {
                                t.path?.let { path ->
                                    discoverySeed = DiscoverySeed(
                                        kind = DiscoverySeedKind.song,
                                        title = t.title ?: path,
                                        path = path,
                                        artist = t.artist,
                                        album = t.album,
                                    )
                                }
                            },
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

@Composable
private fun SpotifyPlaylistHeader(
    repository: BockMediaRepository,
    playlistId: String,
    name: String,
    totalTracks: Int,
    totalDurationSec: Int,
    downloaded: Boolean,
    downloading: Boolean,
    downloadFailed: Boolean,
    downloadProgress: Float,
    onPlay: () -> Unit,
    onPlayOnSpeaker: () -> Unit = {},
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDiscover: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val artUrl by produceState<String?>(initialValue = null, playlistId) {
        value = repository.artworkUrlForPlaylist(playlistId)
    }
    val gradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.background,
        ),
    )
    val trackLabel = if (totalTracks == 1) "1 song" else "$totalTracks songs"
    val durationLabel = if (totalDurationSec > 0) formatTotalDuration(totalDurationSec) else null

    Column(
        Modifier
            .fillMaxWidth()
            .background(gradient)
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 16.dp),
    ) {
        BockArtwork(
            model = artUrl,
            title = name,
            modifier = Modifier.size(180.dp),
            shape = RoundedCornerShape(8.dp),
            fallbackFontSize = 42.sp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Playlist",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append(trackLabel)
                durationLabel?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = BockGreen),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(32.dp),
                    tint = Color.Black,
                )
            }
            IconButton(onClick = onDownload, enabled = !downloading) {
                Icon(
                    when {
                        downloading -> Icons.Default.Download
                        downloaded -> Icons.Default.DownloadDone
                        downloadFailed -> Icons.Default.Download
                        else -> Icons.Default.Download
                    },
                    contentDescription = if (downloaded) "Sync offline" else "Download for offline",
                    tint = when {
                        downloading -> MaterialTheme.colorScheme.onSurfaceVariant
                        downloaded || downloadFailed -> BockGreen
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Play on a speaker") },
                        onClick = { menuExpanded = false; onPlayOnSpeaker() },
                        leadingIcon = { Icon(Icons.Default.Speaker, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Mix Muse / Resonance") },
                        onClick = { menuExpanded = false; onDiscover() },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuExpanded = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete playlist") },
                        onClick = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            downloading -> {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(4.dp),
                        color = BockGreen,
                    )
                    Text(
                        "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = BockGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Downloading for offline playback…",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            downloadFailed -> {
                Text(
                    "Download interrupted — tap download to retry when online",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            downloaded -> {
                Text(
                    "Available offline · tap download to sync new tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = BockGreen,
                )
            }
            else -> {
                Text(
                    "Play opens speaker picker — this phone or Alexa",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyMuted,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotifyTrackRow(
    trackNum: Int,
    track: PlaylistTrack,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDiscover: () -> Unit = {},
    reorderMode: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDiscover,
                enabled = track.path != null,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$trackNum",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                track.title ?: "—",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                track.artist?.takeIf { it.isNotBlank() },
                track.album?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.duration?.let { dur ->
            Text(
                formatDuration(dur),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
        if (reorderMode) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Remove from playlist",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun formatTotalDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 -> "$hours hr $minutes min"
        minutes > 0 -> "$minutes min"
        else -> "< 1 min"
    }
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SmartPlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, String?, Int, Boolean) -> Unit,
    initial: SmartPlaylist? = null,
) {
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var genre by remember(initial) { mutableStateOf("") }
    var artist by remember(initial) { mutableStateOf("") }
    var max by remember(initial) { mutableStateOf("50") }
    var enabled by remember(initial) { mutableStateOf(initial?.enabled ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Smart playlist" else "Edit smart playlist") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") })
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") })
                OutlinedTextField(max, { max = it }, label = { Text("Max tracks") })
                if (initial != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enabled, { enabled = it })
                        Text("Enabled")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(name, genre.ifBlank { null }, artist.ifBlank { null }, max.toIntOrNull() ?: 50, enabled)
            }) { Text(if (initial == null) "Create" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MergeDialog(onDismiss: () -> Unit, onMerge: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge playlists") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Combined name (optional)") }) },
        confirmButton = { TextButton(onClick = { onMerge(name) }) { Text("Merge") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AiPlaylistDialog(repository: BockMediaRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mix Muse") },
        text = {
            Column {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Describe the vibe") }, minLines = 3)
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") })
                if (preview.isNotEmpty()) Text("${preview.size} tracks preview")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        preview = repository.aiPlaylist(prompt, name.ifBlank { "Mix Muse" }, 30, false).preview
                    }
                }) { Text("Preview") }
                TextButton(onClick = {
                    scope.launch {
                        repository.aiPlaylist(prompt, name.ifBlank { "Mix Muse" }, 30, true)
                        onDismiss()
                    }
                }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun isPlaylistEditable(source: String?, sourceName: String?): Boolean {
    if (sourceName.equals("bockmedia", ignoreCase = true)) return true
    return !source.isNullOrBlank()
}
