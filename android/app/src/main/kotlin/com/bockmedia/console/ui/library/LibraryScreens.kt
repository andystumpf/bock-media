package com.bockmedia.console.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import com.bockmedia.console.ui.components.PlayButton
import com.bockmedia.console.ui.components.SearchField
import kotlinx.coroutines.delay

@Composable
fun ArtistsScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
) = LibraryListScreen(
    title = "Artists",
    loadPage = { page, search -> repository.artists(page, search).let { it.items to it.total } },
    label = { it.name },
    sub = { "${it.tracks} tracks · ${it.albums} albums" },
    remoteOk = remoteOk,
    onPlay = { onPlay(PlayTarget.Artist(it.name)) },
    onOpen = { onOpenArtist(it.name) },
)

@Composable
fun AlbumsScreen(
    repository: BockMediaRepository,
    artistFilter: String?,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text(artistFilter ?: "Albums", style = MaterialTheme.typography.titleMedium)
            }
        }
        LibraryListScreen(
            title = if (artistFilter == null) "Albums" else "Albums — $artistFilter",
            loadPage = { page, search ->
                repository.albums(page, search, artistFilter).let { it.items to it.total }
            },
            label = { it.name },
            sub = { "${it.artist ?: ""} · ${it.tracks} tracks" },
            remoteOk = remoteOk,
            onPlay = { onPlay(PlayTarget.Album(it.name, it.artist)) },
            onOpen = { onOpenAlbum(it.name) },
        )
    }
}

@Composable
fun SongsScreen(
    repository: BockMediaRepository,
    artistFilter: String?,
    albumFilter: String?,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        if (onBack != null) {
            Row(Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                Text(listOfNotNull(artistFilter, albumFilter).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
            }
        }
        LibraryListScreen(
            title = "Songs",
            loadPage = { page, search ->
                repository.songs(page, search, artistFilter, albumFilter).let { it.items to it.total }
            },
            label = { it.title ?: "—" },
            sub = { "${it.artist ?: ""} · ${it.album ?: ""}" },
            remoteOk = remoteOk,
            onPlay = { it.path?.let { p -> onPlay(PlayTarget.Song(p, it.title ?: "")) } },
            onOpen = null,
        )
    }
}

@Composable
private fun <T> LibraryListScreen(
    title: String,
    loadPage: suspend (Int, String) -> Pair<List<T>, Int>,
    label: (T) -> String,
    sub: (T) -> String,
    remoteOk: Boolean,
    onPlay: (T) -> Unit,
    onOpen: ((T) -> Unit)?,
) {
    var search by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(1) }
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val limit = if (title == "Songs") 100 else 50

    LaunchedEffect(page, search) {
        delay(350)
        loading = true
        runCatching {
            val (list, t) = loadPage(page, search)
            items = list
            total = t
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(search, { search = it; page = 1 }, "Search")
        Spacer(Modifier.height(8.dp))
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            LazyColumn(Modifier.weight(1f)) {
                items(items) { item ->
                    ListItem(
                        headlineContent = { Text(label(item)) },
                        supportingContent = { Text(sub(item)) },
                        modifier = if (onOpen != null) Modifier.clickable { onOpen(item) } else Modifier,
                        trailingContent = {
                            if (remoteOk) PlayButton(onClick = { onPlay(item) })
                        },
                    )
                }
            }
            PaginationBar(page, ((total + limit - 1) / limit).coerceAtLeast(1)) { page = it }
        }
    }
}
