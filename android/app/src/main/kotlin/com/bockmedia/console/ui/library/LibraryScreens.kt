package com.bockmedia.console.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.LibraryArtListItem
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import com.bockmedia.console.ui.components.PlayButton
import com.bockmedia.console.ui.components.SearchField
import com.bockmedia.console.ui.discovery.AcquireIdeasDialog
import com.bockmedia.console.ui.discovery.DiscoveryActionsDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.runResonanceMix
import com.bockmedia.console.ui.discovery.runResonanceRadio
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun ArtistsScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
) = LibraryListScreen(
    repository = repository,
    title = "Artists",
    loadPage = { page, search -> repository.artists(page, search).let { it.items to it.total } },
    label = { it.name },
    sub = { "${it.tracks} tracks · ${it.albums} albums" },
    remoteOk = remoteOk,
    onPlayItem = { onPlay(PlayTarget.Artist(it.name)) },
    onOpen = { onOpenArtist(it.name) },
    artPath = { null },
    artistName = { it.name },
)

@Composable
fun AlbumsScreen(
    repository: BockMediaRepository,
    artistFilter: String?,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
) {
    LibraryListScreen(
        repository = repository,
        title = if (artistFilter == null) "Albums" else "Albums — $artistFilter",
        showSearch = artistFilter == null,
        loadPage = { page, search ->
            repository.albums(page, search, artistFilter).let { it.items to it.total }
        },
        label = { it.name },
        sub = { "${it.artist ?: ""} · ${it.tracks} tracks" },
        remoteOk = remoteOk,
        onPlayItem = { onPlay(PlayTarget.Album(it.name, it.artist)) },
        onOpen = { onOpenAlbum(it.name, it.artist) },
        artPath = { it.artPath },
        albumName = { it.name },
        artistName = { it.artist },
        showUnplayed = { it.unplayed },
        discoverySeed = { album ->
            DiscoverySeed(
                kind = DiscoverySeedKind.album,
                title = album.name,
                album = album.name,
                artist = album.artist,
            )
        },
    )
}

@Composable
fun SongsScreen(
    repository: BockMediaRepository,
    artistFilter: String?,
    albumFilter: String?,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
) {
    LibraryListScreen(
        repository = repository,
        title = "Songs",
        showSearch = artistFilter == null && albumFilter == null,
        loadPage = { page, search ->
            repository.songs(page, search, artistFilter, albumFilter).let { it.items to it.total }
        },
        label = { it.title ?: "—" },
        sub = { "${it.artist ?: ""} · ${it.album ?: ""}" },
        remoteOk = remoteOk,
        onPlayItem = { item -> item.path?.let { p -> onPlay(PlayTarget.Song(p, item.title ?: "")) } },
        onOpen = null,
        artPath = { it.path },
        discoverySeed = { song ->
            song.path?.let { path ->
                DiscoverySeed(
                    kind = DiscoverySeedKind.song,
                    title = song.title ?: path,
                    path = path,
                    artist = song.artist,
                    album = song.album,
                )
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> LibraryListScreen(
    repository: BockMediaRepository,
    title: String,
    showSearch: Boolean = true,
    loadPage: suspend (Int, String) -> Pair<List<T>, Int>,
    label: (T) -> String,
    sub: (T) -> String,
    remoteOk: Boolean,
    onPlayItem: (T) -> Unit,
    onOpen: ((T) -> Unit)?,
    artPath: ((T) -> String?)? = null,
    artistName: ((T) -> String?)? = null,
    albumName: ((T) -> String?)? = null,
    showUnplayed: ((T) -> Boolean)? = null,
    discoverySeed: ((T) -> DiscoverySeed?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showMixMuse by remember { mutableStateOf(false) }
    var mixMuseSeed by remember { mutableStateOf<DiscoverySeed?>(null) }
    var discoveryDialogSeed by remember { mutableStateOf<DiscoverySeed?>(null) }
    var acquireIdeasSeed by remember { mutableStateOf<DiscoverySeed?>(null) }
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

    mixMuseSeed?.let { seed ->
        if (showMixMuse) {
            MixMuseDialog(
                repository = repository,
                seed = seed,
                onDismiss = { showMixMuse = false; mixMuseSeed = null },
                onPlaylistCreated = { _, _ -> },
            )
        }
    }
    discoveryDialogSeed?.let { seed ->
        DiscoveryActionsDialog(
            seed = seed,
            onDismiss = { discoveryDialogSeed = null },
            onMixMuse = { mixMuseSeed = seed; showMixMuse = true },
            onResonanceRadio = {
                scope.launch { repository.runResonanceRadio(context, seed) }
            },
            onResonanceMix = {
                scope.launch { repository.runResonanceMix(seed) { _, _ -> } }
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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (showSearch) {
            SearchField(search, { search = it; page = 1 }, "Search")
            Spacer(Modifier.height(8.dp))
        }
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(items) { item ->
                    val seed = discoverySeed?.invoke(item)
                    val rowModifier = when {
                        seed != null -> Modifier.combinedClickable(
                            onClick = { onOpen?.invoke(item) },
                            onLongClick = { discoveryDialogSeed = seed },
                        )
                        onOpen != null -> Modifier.clickable { onOpen(item) }
                        else -> Modifier
                    }
                    if (artPath != null || artistName != null || albumName != null) {
                        LibraryArtListItem(
                            repository = repository,
                            title = label(item),
                            subtitle = sub(item),
                            artPath = artPath?.invoke(item),
                            artistName = artistName?.invoke(item),
                            albumName = albumName?.invoke(item),
                            showUnplayed = showUnplayed?.invoke(item) == true,
                            modifier = rowModifier,
                            trailing = {
                                if (remoteOk) PlayButton(onClick = { onPlayItem(item) })
                            },
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(label(item)) },
                            supportingContent = { Text(sub(item)) },
                            modifier = rowModifier,
                            trailingContent = {
                                if (remoteOk) PlayButton(onClick = { onPlayItem(item) })
                            },
                        )
                    }
                }
            }
            PaginationBar(page, ((total + limit - 1) / limit).coerceAtLeast(1)) { page = it }
        }
    }
}
