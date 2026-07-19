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
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.platform.testTag
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
            val loaded = repository.playlists(search, memberScoped = true).items
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

    Column(Modifier.fillMaxSize().testTag(BockTestTags.PLAYLISTS_LIST_BODY)) {
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
        PlaylistTileArt(
            repository = repository,
            playlistId = playlist.id,
            title = playlist.name,
            artPath = playlist.artPath,
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
                    playlistShareBadge(
                        ownerMemberId = playlist.ownerMemberId,
                        ownerName = playlist.ownerName,
                        visibility = playlist.visibility,
                        sharedWith = playlist.sharedWith,
                        daily = playlist.daily,
                        activeMemberId = ActiveProfileStore.activeMemberId(LocalContext.current),
                        memberName = { id ->
                            // Name lookup deferred — badge uses ownerName from API when available
                            null
                        },
                    )?.let { append(" · $it") }
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
