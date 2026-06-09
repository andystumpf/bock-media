package com.bockmedia.console.ui.playlists

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.PlaylistTrack
import com.bockmedia.console.data.api.dto.SmartPlaylist
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.components.BockPullRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var showNew by remember { mutableStateOf(false) }
    var showSmart by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(1) }
    val pageSize = 100

    suspend fun load() {
        runCatching {
            playlists = repository.playlists(search).items
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
        onCreate = { name, genre, artist, max ->
            scope.launch { repository.createSmartPlaylist(name, genre, artist, max); load(); showSmart = false }
        },
    )
    if (showMerge) MergeDialog(
        onDismiss = { showMerge = false },
        onMerge = { name ->
            scope.launch {
                repository.mergePlaylists(selectedMerge.toList(), name.ifBlank { null })
                selectedMerge = emptySet()
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
                IconButton(onClick = { showAi = true }) { Icon(Icons.Default.Psychology, "AI") }
                IconButton(
                    onClick = { showMerge = true },
                    enabled = selectedMerge.size >= 2,
                ) { Icon(Icons.Default.Merge, "Merge") }
            }
        }
        if (smart.isNotEmpty()) {
            Text("Smart playlists", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
            smart.forEach { sp ->
                ListItem(
                    headlineContent = { Text(sp.name) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { scope.launch { repository.refreshSmartPlaylist(sp.id); load() } }) { Text("Refresh") }
                            TextButton(onClick = { scope.launch { repository.deleteSmartPlaylist(sp.id); load() } }) { Text("Delete") }
                        }
                    },
                )
            }
        }
        BockLazyColumn(Modifier.weight(1f)) {
            items(paged, key = { it.id }) { pl ->
                ListItem(
                    headlineContent = { Text(pl.name) },
                    supportingContent = { Text("${pl.tracks} tracks · ${pl.sourceName ?: pl.source ?: ""}") },
                    leadingContent = {
                        Checkbox(checked = pl.id in selectedMerge, onCheckedChange = { checked ->
                            selectedMerge = if (checked) selectedMerge + pl.id else selectedMerge - pl.id
                        })
                    },
                    trailingContent = {
                        Row {
                            if (remoteOk) PlayButton(onClick = { onPlay(PlayTarget.Playlist(pl.id, pl.name)) })
                            IconButton(onClick = { onOpenDetail(pl.id) }) { Icon(Icons.Default.ChevronRight, null) }
                        }
                    },
                )
            }
        }
        PaginationBar(page, totalPages) { page = it }
    }
}

@Composable
fun PlaylistDetailScreen(
    repository: BockMediaRepository,
    playlistId: String,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var editName by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("title") }
    var sortOrder by remember { mutableStateOf("asc") }
    var page by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val pageSize = 50

    suspend fun load() {
        loading = tracks.isEmpty()
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
            tracks = d.tracks
            total = d.total.takeIf { it > 0 } ?: d.tracks.size
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(playlistId, page, sortBy, sortOrder) { load() }
    LaunchedEffect(filter) {
        delay(400)
        page = 1
        load()
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = { BockTextField(editName, { editName = it }, "Name") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repository.renamePlaylist(playlistId, editName.trim())
                        showRename = false
                        load()
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text("$total tracks", style = MaterialTheme.typography.labelSmall)
            }
            if (remoteOk) PlayButton(onClick = { onPlay(PlayTarget.Playlist(playlistId, name)) })
            IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = {
                scope.launch { repository.deletePlaylist(playlistId); onBack() }
            }) { Icon(Icons.Default.Delete, null) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SearchField(filter, { filter = it }, "Filter tracks")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip("Title", sortBy == "title") { sortBy = "title"; page = 1 }
                SortChip("Artist", sortBy == "artist") { sortBy = "artist"; page = 1 }
                SortChip("Album", sortBy == "album") { sortBy = "album"; page = 1 }
                SortChip("↑", sortOrder == "asc") { sortOrder = "asc"; page = 1 }
                SortChip("↓", sortOrder == "desc") { sortOrder = "desc"; page = 1 }
            }
        }
        if (loading && tracks.isEmpty()) {
            LoadingBox(Modifier.weight(1f))
        } else {
            BockPullRefresh(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; scope.launch { load() } },
                modifier = Modifier.weight(1f),
            ) {
                BockLazyColumn(Modifier.fillMaxSize()) {
                    items(tracks.size) { idx ->
                        val t = tracks[idx]
                        val trackNum = (page - 1) * pageSize + idx + 1
                        ListItem(
                            leadingContent = { Text("$trackNum", style = MaterialTheme.typography.labelMedium) },
                            headlineContent = { Text(t.title ?: "—") },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        t.artist?.takeIf { it.isNotBlank() },
                                        t.album?.takeIf { it.isNotBlank() },
                                        t.duration?.let { formatDuration(it) },
                                    ).joinToString(" · "),
                                )
                            },
                            trailingContent = {
                                Row {
                                    if (remoteOk && t.path != null) {
                                        PlayButton(onClick = { onPlay(PlayTarget.Song(t.path, t.title ?: "")) })
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            t.path?.let { repository.removePlaylistTrack(playlistId, it) }
                                            load()
                                        }
                                    }) { Icon(Icons.Default.Remove, null) }
                                }
                            },
                        )
                    }
                }
            }
            PaginationBar(page, ((total + pageSize - 1) / pageSize).coerceAtLeast(1)) { page = it }
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
private fun SmartPlaylistDialog(onDismiss: () -> Unit, onCreate: (String, String?, String?, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("50") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smart playlist") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") })
                OutlinedTextField(genre, { genre = it }, label = { Text("Genre") })
                OutlinedTextField(artist, { artist = it }, label = { Text("Artist") })
                OutlinedTextField(max, { max = it }, label = { Text("Max tracks") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCreate(name, genre.ifBlank { null }, artist.ifBlank { null }, max.toIntOrNull() ?: 50)
            }) { Text("Create") }
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
        title = { Text("AI playlist") },
        text = {
            Column {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") })
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") })
                if (preview.isNotEmpty()) Text("${preview.size} tracks preview")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        preview = repository.aiPlaylist(prompt, name.ifBlank { "AI Playlist" }, 30, false).preview
                    }
                }) { Text("Preview") }
                TextButton(onClick = {
                    scope.launch {
                        repository.aiPlaylist(prompt, name, 30, true)
                        onDismiss()
                    }
                }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
