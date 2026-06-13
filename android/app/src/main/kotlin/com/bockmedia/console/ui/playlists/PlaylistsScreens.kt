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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadGen by remember { mutableIntStateOf(0) }

    suspend fun load(expectedGen: Int) {
        if (expectedGen != loadGen) return
        error = null
        runCatching {
            coroutineScope {
                val pl = async { repository.playlists(search, page, pageSize) }
                val sm = async { repository.smartPlaylists() }
                val resp = pl.await()
                if (expectedGen != loadGen) return@coroutineScope
                playlists = resp.items
                total = resp.total
                smart = sm.await().items
            }
        }.onFailure {
            if (expectedGen == loadGen) {
                error = it.message ?: "Failed to load playlists"
                playlists = emptyList()
            }
        }
    }

    LaunchedEffect(Unit) { loadGen++; load(loadGen) }
    LaunchedEffect(search) {
        delay(350)
        page = 1
        loadGen++
        load(loadGen)
    }
    LaunchedEffect(page) {
        loadGen++
        load(loadGen)
    }

    val totalPages = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)

    if (showNew) NewPlaylistDialog(
        onDismiss = { showNew = false },
        onCreate = { name ->
            scope.launch {
                runCatching {
                    repository.createPlaylist(name)
                    loadGen++
                    load(loadGen)
                    showNew = false
                }.onFailure { error = it.message ?: "Create failed" }
            }
        },
    )
    if (showSmart) SmartPlaylistDialog(
        onDismiss = { showSmart = false },
        onCreate = { name, genre, artist, max ->
            scope.launch {
                runCatching {
                    repository.createSmartPlaylist(name, genre, artist, max)
                    loadGen++
                    load(loadGen)
                    showSmart = false
                }.onFailure { error = it.message ?: "Create failed" }
            }
        },
    )
    if (showMerge) MergeDialog(
        onDismiss = { showMerge = false },
        onMerge = { name ->
            scope.launch {
                runCatching {
                    repository.mergePlaylists(selectedMerge.toList(), name.ifBlank { null })
                    selectedMerge = emptySet()
                    loadGen++
                    load(loadGen)
                    showMerge = false
                }.onFailure { error = it.message ?: "Merge failed" }
            }
        },
    )
    if (showAi) AiPlaylistDialog(
        repository = repository,
        onDismiss = { showAi = false; scope.launch { loadGen++; load(loadGen) } },
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchField(search, { search = it }, "Search playlists")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                            TextButton(onClick = { scope.launch { runCatching { repository.refreshSmartPlaylist(sp.id); loadGen++; load(loadGen) }.onFailure { error = it.message } } }) { Text("Refresh") }
                            TextButton(onClick = { scope.launch { runCatching { repository.deleteSmartPlaylist(sp.id); loadGen++; load(loadGen) }.onFailure { error = it.message } } }) { Text("Delete") }
                        }
                    },
                )
            }
        }
        BockLazyColumn(Modifier.weight(1f)) {
            items(playlists, key = { it.id }) { pl ->
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
                            IconButton(onClick = { onOpenDetail(pl.id) }) { Icon(Icons.Default.ChevronRight, "Open playlist") }
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
    var name by remember(playlistId) { mutableStateOf("") }
    var tracks by remember(playlistId) { mutableStateOf<List<PlaylistTrack>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadGen by remember { mutableIntStateOf(0) }

    suspend fun load(expectedGen: Int) {
        if (tracks.isEmpty()) loading = true
        error = null
        runCatching {
            val d = repository.playlistDetail(playlistId, q = filter.ifBlank { null })
            if (expectedGen != loadGen) return
            name = d.name
            val all = d.tracks.toMutableList()
            var page = 1
            while (all.size < d.total && page < 50) {
                page++
                val next = repository.playlistDetail(playlistId, page = page, q = filter.ifBlank { null })
                if (expectedGen != loadGen) return
                if (next.tracks.isEmpty()) break
                all += next.tracks
            }
            tracks = all
        }.onFailure {
            if (expectedGen == loadGen) {
                error = it.message ?: "Failed to load playlist"
                tracks = emptyList()
            }
        }
        if (expectedGen == loadGen) loading = false
    }

    LaunchedEffect(playlistId) {
        loadGen++
        filter = ""
        name = ""
        tracks = emptyList()
        load(loadGen)
    }
    LaunchedEffect(filter) {
        if (filter.isNotEmpty()) delay(400)
        loadGen++
        load(loadGen)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (remoteOk) PlayButton(onClick = { onPlay(PlayTarget.Playlist(playlistId, name)) })
            IconButton(onClick = {
                scope.launch {
                    runCatching { repository.deletePlaylist(playlistId) }
                        .onSuccess { onBack() }
                        .onFailure { error = it.message ?: "Delete failed" }
                }
            }) { Icon(Icons.Default.Delete, "Delete playlist") }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SearchField(filter, { filter = it }, "Filter tracks")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (loading && tracks.isEmpty()) LoadingBox(Modifier.weight(1f)) else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(tracks) { t ->
                    ListItem(
                        headlineContent = { Text(t.title ?: "—") },
                        supportingContent = { Text("${t.artist ?: ""} · ${t.album ?: ""}") },
                        trailingContent = {
                            Row {
                                if (remoteOk && t.path != null) {
                                    PlayButton(onClick = { onPlay(PlayTarget.Song(t.path, t.title ?: "")) })
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching {
                                            t.path?.let { repository.removePlaylistTrack(playlistId, it) }
                                        }.onSuccess {
                                            loadGen++
                                            load(loadGen)
                                        }.onFailure {
                                            error = it.message ?: "Remove failed"
                                        }
                                    }
                                }) { Icon(Icons.Default.Remove, "Remove track") }
                            }
                        },
                    )
                }
            }
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
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI playlist") },
        text = {
            Column {
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") })
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") })
                if (preview.isNotEmpty()) Text("${preview.size} tracks preview")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    scope.launch {
                        error = null
                        runCatching {
                            preview = repository.aiPlaylist(prompt, name.ifBlank { "AI Playlist" }, 30, false).resultTracks()
                        }.onFailure { error = it.message ?: "Preview failed" }
                    }
                }) { Text("Preview") }
                TextButton(onClick = {
                    scope.launch {
                        error = null
                        runCatching {
                            repository.aiPlaylist(prompt, name, 30, true)
                        }.onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "Create failed" }
                    }
                }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
