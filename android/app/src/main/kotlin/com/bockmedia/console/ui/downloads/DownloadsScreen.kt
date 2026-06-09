package com.bockmedia.console.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineCollectionStatus
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.formatDownloadDate
import com.bockmedia.console.local.formatOfflineBytes
import com.bockmedia.console.local.toPlayTarget
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockLazyColumn
import kotlinx.coroutines.launch

private val SpotifyGreen = Color(0xFF1DB954)
private val SpotifySheetBg = Color(0xFF121212)

@Composable
fun DownloadsScreen(
    onOpenPlaylist: (String) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    onOpenNowPlaying: () -> Unit = {},
) {
    DownloadsManagementSection(
        modifier = Modifier.fillMaxSize(),
        embedded = false,
        onOpenPlaylist = onOpenPlaylist,
        snackbarHostState = snackbarHostState,
        onOpenNowPlaying = onOpenNowPlaying,
    )
}

@Composable
fun DownloadsManagementSection(
    modifier: Modifier = Modifier,
    embedded: Boolean = true,
    onOpenPlaylist: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    onOpenNowPlaying: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val statuses by OfflineDownloadManager.statuses.collectAsState()
    var detailStatus by remember { mutableStateOf<OfflineCollectionStatus?>(null) }
    val store = remember { OfflineDownloadStore(context) }
    val repository = remember { BockMediaApp.get(context).repository }

    LaunchedEffect(Unit) {
        OfflineDownloadManager.refresh(context)
    }

    val sorted = remember(statuses) {
        statuses.values.sortedWith(
            compareBy<OfflineCollectionStatus> {
                when (it.state) {
                    DownloadState.Downloading -> 0
                    DownloadState.Failed -> 1
                    DownloadState.Complete -> 2
                    DownloadState.Idle -> 3
                }
            }.thenByDescending { it.manifest.downloadedAtMs },
        )
    }
    val storageBytes = remember(statuses) { store.bytesOnDisk() }

    detailStatus?.let { status ->
        DownloadDetailScreen(
            status = status,
            store = store,
            repository = repository,
            onBack = { detailStatus = null },
            onPlay = { startIndex ->
                scope.launch {
                    LocalPlaybackController.playOfflineManifest(
                        context,
                        status.manifest,
                        shuffle = false,
                        startIndex = startIndex,
                    )
                    val err = LocalPlaybackController.state.value.error
                    if (err != null) {
                        snackbarHostState?.showSnackbar(err)
                    } else {
                        snackbarHostState?.showSnackbar("Playing \"${status.manifest.title}\" on this phone")
                        detailStatus = null
                        onOpenNowPlaying()
                    }
                }
            },
            onDelete = {
                OfflineDownloadManager.deleteCollection(context, status.manifest.id)
                detailStatus = null
            },
            onDownload = {
                when (status.state) {
                    DownloadState.Failed -> OfflineDownloadManager.retry(context, status.manifest.id)
                    DownloadState.Complete -> OfflineDownloadManager.resync(context, status.manifest.toPlayTarget())
                    else -> OfflineDownloadManager.resync(context, status.manifest.toPlayTarget())
                }
            },
            onOpenPlaylist = status.manifest.sourcePlaylistId?.let { id ->
                { detailStatus = null; onOpenPlaylist(id) }
            },
        )
        return
    }

    Column(modifier) {
        DownloadsStorageSummary(
            storageBytes = storageBytes,
            completeCount = sorted.count { it.state == DownloadState.Complete },
            activeCount = sorted.count { it.state == DownloadState.Downloading },
            embedded = embedded,
        )

        if (sorted.isEmpty()) {
            DownloadsEmptyState(embedded = embedded)
        } else if (embedded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column {
                    sorted.forEach { status ->
                        DownloadListRow(
                            status = status,
                            store = store,
                            repository = repository,
                            onClick = { detailStatus = status },
                            onRetry = { OfflineDownloadManager.retry(context, status.manifest.id) },
                        )
                        if (status != sorted.last()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        } else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(sorted.size, key = { sorted[it].manifest.id }) { index ->
                    DownloadListRow(
                        status = sorted[index],
                        store = store,
                        repository = repository,
                        onClick = { detailStatus = sorted[index] },
                        onRetry = { OfflineDownloadManager.retry(context, sorted[index].manifest.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadDetailScreen(
    status: OfflineCollectionStatus,
    store: OfflineDownloadStore,
    repository: BockMediaRepository,
    onBack: () -> Unit,
    onPlay: (startIndex: Int) -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onOpenPlaylist: (() -> Unit)?,
) {
    val manifest = status.manifest
    val kindLabel = manifest.kind.replaceFirstChar { it.uppercase() }
    val sizeBytes = store.collectionBytesOnDisk(manifest.id)

    Scaffold(
        containerColor = SpotifySheetBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        manifest.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete from device",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SpotifySheetBg,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = MaterialTheme.colorScheme.error,
                ),
            )
        },
        bottomBar = {
            DownloadDetailBottomBar(
                status = status,
                onPlay = { onPlay(0) },
                onDownload = onDownload,
            )
        },
    ) { padding ->
        BockLazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    DownloadArtwork(
                        repository = repository,
                        manifest = manifest,
                        size = 160.dp,
                        cornerRadius = 8.dp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        manifest.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Spacer(Modifier.height(12.dp))
                    DetailRow("Type", kindLabel)
                    DetailRow("Tracks", "${manifest.tracks.size}")
                    if (status.state == DownloadState.Complete) {
                        DetailRow("Size on device", formatOfflineBytes(sizeBytes))
                        DetailRow("Downloaded", formatDownloadDate(manifest.downloadedAtMs))
                    }
                    when (status.state) {
                        DownloadState.Downloading -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Downloading… ${(status.progress * 100).toInt()}%",
                                color = Color.White.copy(alpha = 0.75f),
                            )
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                color = SpotifyGreen,
                            )
                        }
                        DownloadState.Failed -> {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                status.error ?: "Download failed",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> Unit
                    }
                    onOpenPlaylist?.let { open ->
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = open) {
                            Text("View playlist on server")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tracks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(manifest.tracks.size, key = { manifest.tracks[it].path }) { index ->
                val track = manifest.tracks[index]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPlay(index) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        track.artist?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (index < manifest.tracks.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DownloadDetailBottomBar(
    status: OfflineCollectionStatus,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        color = SpotifySheetBg,
        tonalElevation = 8.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (status.state) {
                DownloadState.Complete -> {
                    Surface(
                        onClick = onPlay,
                        shape = RoundedCornerShape(12.dp),
                        color = SpotifyGreen,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("Play on this phone", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Surface(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.DownloadDone, null, tint = SpotifyGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("Sync new tracks", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                DownloadState.Failed -> {
                    Surface(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        color = SpotifyGreen,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Refresh, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry download", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                DownloadState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { status.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = SpotifyGreen,
                    )
                    Text(
                        "Downloading… ${(status.progress * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                DownloadState.Idle -> {
                    Surface(
                        onClick = onDownload,
                        shape = RoundedCornerShape(12.dp),
                        color = SpotifyGreen,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Download, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("Download", color = Color.Black, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsStorageSummary(
    storageBytes: Long,
    completeCount: Int,
    activeCount: Int,
    embedded: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (embedded) 0.dp else 16.dp,
                vertical = if (embedded) 0.dp else 16.dp,
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (embedded) 0.35f else 0.45f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Offline storage", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                formatOfflineBytes(storageBytes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                buildString {
                    append(if (completeCount == 1) "1 collection" else "$completeCount collections")
                    append(" on this device")
                    if (activeCount > 0) append(" · $activeCount downloading")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (embedded) Spacer(Modifier.height(12.dp))
}

@Composable
private fun DownloadsEmptyState(embedded: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (embedded) 8.dp else 32.dp, horizontal = if (embedded) 0.dp else 32.dp),
        horizontalAlignment = if (embedded) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Nothing downloaded yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Long-press playlists, mixes, or radio on Home to save them for offline playback on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun DownloadListRow(
    status: OfflineCollectionStatus,
    store: OfflineDownloadStore,
    repository: BockMediaRepository,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val manifest = status.manifest
    val kindLabel = manifest.kind.replaceFirstChar { it.uppercase() }
    val sizeLabel = if (status.state == DownloadState.Complete) {
        formatOfflineBytes(store.collectionBytesOnDisk(manifest.id))
    } else null

    ListItem(
        leadingContent = {
            DownloadArtwork(repository = repository, manifest = manifest, size = 56.dp)
        },
        headlineContent = { Text(manifest.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (status.state) {
                    DownloadState.Downloading -> {
                        Text("Downloading… ${(status.progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = SpotifyGreen,
                        )
                    }
                    DownloadState.Complete -> {
                        Text(
                            buildString {
                                append(kindLabel)
                                append(" · ${manifest.tracks.size} tracks")
                                sizeLabel?.let { append(" · $it") }
                            },
                        )
                    }
                    DownloadState.Failed -> {
                        Text(
                            status.error ?: "Download failed",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    DownloadState.Idle -> Text("Queued")
                }
            }
        },
        trailingContent = {
            when (status.state) {
                DownloadState.Failed -> {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
                    }
                }
                else -> {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Details")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
