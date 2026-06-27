package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import com.bockmedia.console.ui.components.StarRatingBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchRadioHit
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.SearchResultsSessionCache
import com.bockmedia.console.local.SearchRecentSelection
import com.bockmedia.console.domain.model.SearchSuggestionKind
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.ui.components.AddToPlaylistSheet
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.PhonePlayback
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.testing.BockTestTags
import kotlinx.coroutines.launch

private data class SearchSection(
    val key: String,
    val title: String,
    val items: List<Any>,
    val showChevron: Boolean = false,
)

@Composable
fun SearchResultsList(
    repository: BockMediaRepository,
    results: SearchResponse,
    query: String,
    searchSource: String?,
    remoteOk: Boolean,
    songRatings: Map<String, Int>,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState? = null,
    onRatingChange: (String, SearchHit, Int) -> Unit,
    onRecordSelection: (SearchRecentSelection) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addToPlaylist by remember { mutableStateOf<Pair<String, String>?>(null) }
    val expanded = remember {
        mutableStateMapOf<String, Boolean>().apply {
            SearchResultsSessionCache.expandedSections.forEach { put(it, true) }
        }
    }
    var expandedData by remember { mutableStateOf(SearchResultsSessionCache.expandedData.toMap()) }

    LaunchedEffect(expanded.keys.toList(), expandedData) {
        SearchResultsSessionCache.expandedSections.clear()
        SearchResultsSessionCache.expandedSections.addAll(expanded.filter { it.value }.keys)
        SearchResultsSessionCache.expandedData.clear()
        SearchResultsSessionCache.expandedData.putAll(expandedData)
    }

    addToPlaylist?.let { (path, title) ->
        AddToPlaylistSheet(
            repository = repository,
            trackPath = path,
            trackTitle = title,
            onDismiss = { addToPlaylist = null },
            onAdded = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
    }

    val preview = results.preview
    val sections = buildList {
        if (results.songs.isNotEmpty()) {
            add(SearchSection("songs", "Tracks", results.songs))
        }
        if (results.artists.isNotEmpty()) {
            add(SearchSection("artists", "Artists", results.artists))
        }
        if (results.albums.isNotEmpty()) {
            val total = results.counts["albums"] ?: results.albums.size
            add(SearchSection("albums", "Albums", results.albums, showChevron = total > preview))
        }
        if (results.radios.isNotEmpty()) add(SearchSection("radios", "Radio", results.radios))
        if (results.similar.isNotEmpty()) add(SearchSection("similar", "Sonically similar", results.similar))
        if (results.playlists.isNotEmpty()) add(SearchSection("playlists", "Playlists", results.playlists))
        if (results.smartPlaylists.isNotEmpty()) {
            add(SearchSection("smartPlaylists", "Smart playlists", results.smartPlaylists))
        }
        if (results.genres.isNotEmpty()) add(SearchSection("genres", "Genres", results.genres))
        if (results.rooms.isNotEmpty()) add(SearchSection("rooms", "Rooms", results.rooms))
        if (results.messages.isNotEmpty()) add(SearchSection("messages", "Messages", results.messages))
    }

    BockLazyColumn(Modifier.testTag(BockTestTags.SEARCH_RESULTS)) {
        sections.forEach { section ->
            val total = results.counts[section.key] ?: section.items.size
            val isExpanded = expanded[section.key] == true
            val displayItems = if (isExpanded) {
                val extra = expandedData[section.key]
                if (extra == null) section.items else sectionItems(extra, section.key)
            } else {
                section.items
            }

            item(key = "hdr-${section.key}") {
                PlexampSectionHeader(
                    title = section.title,
                    showChevron = section.showChevron && !isExpanded,
                    onClick = if (section.showChevron && !isExpanded) {
                        {
                            expanded[section.key] = true
                            scope.launch {
                                runCatching {
                                    val full = repository.search(
                                        q = query,
                                        limit = 50,
                                        preview = 50,
                                        section = section.key,
                                        source = searchSource,
                                    )
                                    expandedData = expandedData + (section.key to full)
                                }
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            items(displayItems, key = { item -> "${section.key}-${itemKey(item)}" }) { item ->
                when (section.key) {
                    "radios" -> RadioRow(
                        repository = repository,
                        radio = item as SearchRadioHit,
                        remoteOk = remoteOk,
                        onPlay = onPlay,
                    )
                    "similar", "songs" -> SongHitRow(
                        repository = repository,
                        hit = item as SearchHit,
                        remoteOk = remoteOk,
                        songRatings = songRatings,
                        onPlay = onPlay,
                        onRatingChange = onRatingChange,
                        onAddToPlaylist = { path, title -> addToPlaylist = path to title },
                        snackbarHostState = snackbarHostState,
                    )
                    "playlists", "smartPlaylists" -> PlaylistHitRow(
                        repository = repository,
                        hit = item as SearchHit,
                        onOpenPlaylist = onOpenPlaylist,
                        onPlay = onPlay,
                    )
                    "artists" -> {
                        val hit = item as SearchHit
                        ArtistHitRow(
                            repository = repository,
                            hit = hit,
                            onOpenArtist = { name ->
                                SearchRecentSelection.fromHit("artist", hit)?.let(onRecordSelection)
                                onOpenArtist(name)
                            },
                            onPlay = onPlay,
                        )
                    }
                    "albums" -> {
                        val hit = item as SearchHit
                        AlbumHitRow(
                            repository = repository,
                            hit = hit,
                            onOpenAlbum = { album, artist ->
                                SearchRecentSelection.fromHit("album", hit)?.let(onRecordSelection)
                                onOpenAlbum(album, artist)
                            },
                            onPlay = onPlay,
                        )
                    }
                    "genres" -> GenreHitRow(
                        repository = repository,
                        hit = item as SearchHit,
                        onOpenGenre = onOpenGenre,
                        onPlay = onPlay,
                    )
                    "rooms" -> RoomHitRow(
                        repository = repository,
                        hit = item as SearchHit,
                    )
                    "messages" -> SongHitRow(
                        repository = repository,
                        hit = item as SearchHit,
                        remoteOk = remoteOk,
                        songRatings = songRatings,
                        onPlay = onPlay,
                        onRatingChange = onRatingChange,
                        onAddToPlaylist = { path, title -> addToPlaylist = path to title },
                        snackbarHostState = snackbarHostState,
                    )
                }
            }
            if (total > preview && !isExpanded && !section.showChevron) {
                item(key = "more-${section.key}") {
                    Text(
                        "Show all $total",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                expanded[section.key] = true
                                scope.launch {
                                    runCatching {
                                        val full = repository.search(
                                            q = query,
                                            limit = 50,
                                            preview = 50,
                                            section = section.key,
                                            source = searchSource,
                                        )
                                        expandedData = expandedData + (section.key to full)
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp)) {
                    Text("No results for \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun sectionItems(response: SearchResponse, key: String): List<Any> = when (key) {
    "radios" -> response.radios
    "similar" -> response.similar
    "playlists" -> response.playlists
    "smartPlaylists" -> response.smartPlaylists
    "artists" -> response.artists
    "albums" -> response.albums
    "genres" -> response.genres
    "songs" -> response.songs
    "rooms" -> response.rooms
    "messages" -> response.messages
    else -> emptyList()
}

private fun itemKey(item: Any): String = when (item) {
    is SearchHit -> item.id ?: item.path ?: item.name ?: item.title ?: item.hashCode().toString()
    is SearchRadioHit -> item.path ?: item.name ?: item.displayTitle ?: item.hashCode().toString()
    else -> item.hashCode().toString()
}

@Composable
private fun PlexampSectionHeader(
    title: String,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Show all",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RadioRow(
    repository: BockMediaRepository,
    radio: SearchRadioHit,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
) {
    val title = radio.displayTitle ?: "${radio.name} Radio"
    val target = when (radio.kind?.lowercase()) {
        "song" -> PlayTarget.Radio(title, PlayTarget.RadioSeedKind.Song, radio.name ?: title, radio.path)
        "album" -> PlayTarget.Radio(title, PlayTarget.RadioSeedKind.Artist, radio.artist ?: radio.name ?: title, radio.path)
        else -> PlayTarget.Radio(title, PlayTarget.RadioSeedKind.Artist, radio.name ?: title, radio.path)
    }
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Song,
        hit = SearchHit(name = title, artist = radio.artist, path = radio.path),
        title = title,
        subtitle = "Radio",
        onClick = { onPlay(target) },
        menuItems = listOf(SearchMenuAction("Play radio") { onPlay(target) }),
    )
}

@Composable
private fun PlaylistHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
    onOpenPlaylist: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val id = hit.id ?: return
    val target = PlayTarget.Playlist(id, hit.name ?: "")
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Playlist,
        hit = hit,
        title = hit.name ?: "",
        subtitle = null,
        onClick = { onOpenPlaylist(id) },
        menuItems = listOf(
            SearchMenuAction("Open") { onOpenPlaylist(id) },
            SearchMenuAction("Play") { onPlay(target) },
        ),
    )
}

@Composable
private fun ArtistHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
    onOpenArtist: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val name = hit.name ?: return
    val target = PlayTarget.Artist(name)
    val albumLabel = hit.albums?.let { count ->
        if (count == 1) "1 album" else "$count albums"
    }
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Artist,
        hit = hit,
        title = name,
        subtitle = albumLabel,
        onClick = { onOpenArtist(name) },
        menuItems = listOf(
            SearchMenuAction("Open") { onOpenArtist(name) },
            SearchMenuAction("Play") { onPlay(target) },
        ),
    )
}

@Composable
private fun AlbumHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
    onOpenAlbum: (String, String?) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val name = hit.name ?: return
    val target = PlayTarget.Album(name, hit.artist)
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Album,
        hit = hit,
        title = name,
        subtitle = hit.artist,
        onClick = { onOpenAlbum(name, hit.artist) },
        menuItems = listOf(
            SearchMenuAction("Open") { onOpenAlbum(name, hit.artist) },
            SearchMenuAction("Play") { onPlay(target) },
        ),
    )
}

@Composable
private fun GenreHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
    onOpenGenre: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val name = hit.name ?: return
    val target = PlayTarget.Radio("$name Radio", PlayTarget.RadioSeedKind.Genre, name)
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Album,
        hit = hit,
        title = name,
        subtitle = "Genre",
        onClick = { onOpenGenre(name) },
        menuItems = listOf(
            SearchMenuAction("Open") { onOpenGenre(name) },
            SearchMenuAction("Play radio") { onPlay(target) },
        ),
    )
}

@Composable
private fun RoomHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
) {
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Song,
        hit = hit,
        title = hit.name ?: "Room",
        subtitle = "Alexa device",
        onClick = {},
        menuItems = emptyList(),
    )
}

@Composable
private fun SongHitRow(
    repository: BockMediaRepository,
    hit: SearchHit,
    remoteOk: Boolean,
    songRatings: Map<String, Int>,
    onPlay: (PlayTarget) -> Unit,
    onRatingChange: (String, SearchHit, Int) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = hit.path.orEmpty()
    var showRateDialog by remember { mutableStateOf(false) }
    var showAddToRoom by remember { mutableStateOf(false) }
    var dialogStars by remember(path) { mutableIntStateOf(songRatings[path] ?: 0) }
    val target = PlayTarget.Song(path, hit.title ?: "")
    val canPlay = remoteOk || OfflineDownloadManager.isDownloaded(context, target)
    val playFn: () -> Unit = {
        if (remoteOk) {
            onPlay(target)
        } else {
            scope.launch {
                val err = PhonePlayback.playLocally(context, target)
                err?.let { snackbarHostState?.showSnackbar(it) }
            }
        }
    }
    if (showRateDialog && path.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showRateDialog = false },
            title = { Text(hit.title ?: hit.name ?: "Track") },
            text = {
                StarRatingBar(
                    stars = dialogStars,
                    onStarsChange = { dialogStars = it },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRateDialog = false
                    onRatingChange(path, hit, dialogStars)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRateDialog = false }) { Text("Cancel") }
            },
        )
    }
    SearchHitRow(
        repository = repository,
        kind = SearchSuggestionKind.Song,
        hit = hit,
        title = hit.title ?: hit.name ?: "",
        subtitle = hit.artist,
        onClick = playFn,
        menuItems = buildList {
            if (canPlay) add(SearchMenuAction("Play", playFn))
            if (path.isNotBlank()) {
                add(SearchMenuAction("Add to playlist") { onAddToPlaylist(path, hit.title ?: hit.name ?: "Track") })
                if (remoteOk) {
                    add(SearchMenuAction("Add to room") { showAddToRoom = true })
                }
                val rated = (songRatings[path] ?: 0) > 0
                add(SearchMenuAction(if (rated) "Change rating" else "Rate track") {
                    dialogStars = songRatings[path] ?: 0
                    showRateDialog = true
                })
            }
        },
    )
    if (showAddToRoom && path.isNotBlank()) {
        com.bockmedia.console.ui.components.AddToRoomSheet(
            repository = repository,
            path = path,
            track = hit.title ?: hit.name ?: "Track",
            artist = hit.artist,
            remoteOk = remoteOk,
            onDismiss = { showAddToRoom = false },
            onSuccess = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
            onError = { msg -> scope.launch { snackbarHostState?.showSnackbar(msg) } },
        )
    }
}

private data class SearchMenuAction(val label: String, val action: () -> Unit)

@Composable
private fun SearchHitRow(
    repository: BockMediaRepository,
    kind: SearchSuggestionKind,
    hit: SearchHit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    menuItems: List<SearchMenuAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val variantKey = hit.id ?: hit.path ?: title
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = hit.path,
        playlistId = if (kind == SearchSuggestionKind.Playlist) hit.id else null,
        artistName = if (kind == SearchSuggestionKind.Artist) title else null,
        albumName = if (kind == SearchSuggestionKind.Album) title else hit.album,
        albumArtist = hit.artist,
        variantKey = variantKey,
    )
    val shape = if (kind == SearchSuggestionKind.Artist) RoundedCornerShape(24.dp) else RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BockArtwork(
            model = artUrl,
            title = title,
            modifier = Modifier.size(48.dp),
            shape = shape,
            fallbackFontSize = 16.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (menuItems.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                menuOpen = false
                                item.action()
                            },
                        )
                    }
                }
            }
        }
    }
}
