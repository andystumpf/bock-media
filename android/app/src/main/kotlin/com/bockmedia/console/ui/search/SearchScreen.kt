package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import androidx.compose.material.icons.filled.PlaylistAdd
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.SearchHistoryStore
import com.bockmedia.console.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class SearchView { Browse, NewReleases }

@Composable
fun SearchScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit = {},
    onAccountNavigate: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val historyStore = remember { SearchHistoryStore(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResponse?>(null) }
    var searchLoading by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var browseFeed by remember { mutableStateOf(SearchBrowseSessionCache.peek()) }
    var browseLoading by remember { mutableStateOf(SearchBrowseSessionCache.peek() == null) }
    var recentQueries by remember { mutableStateOf<List<String>>(emptyList()) }
    var favoritePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var browseView by remember { mutableStateOf(SearchView.Browse) }

    LaunchedEffect(Unit) {
        runCatching { favoritePaths = repository.favorites().map { it.path }.toSet() }
        if (SearchBrowseSessionCache.getIfFresh() != null) {
            browseFeed = SearchBrowseSessionCache.peek()
            browseLoading = false
            scope.launch {
                runCatching {
                    val fresh = SearchBrowseLoader.load(repository)
                    SearchBrowseSessionCache.put(fresh)
                    browseFeed = fresh
                }
            }
        } else {
            browseLoading = browseFeed == null
            runCatching {
                val fresh = SearchBrowseLoader.load(repository)
                SearchBrowseSessionCache.put(fresh)
                browseFeed = fresh
            }
            browseLoading = false
        }
    }

    LaunchedEffect(historyStore) {
        historyStore.queries.collectLatest { recentQueries = it }
    }

    LaunchedEffect(query) {
        delay(250)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            results = null
            searchLoading = false
            return@LaunchedEffect
        }
        if (trimmed.length < 2) {
            results = null
            searchLoading = false
            suggestions = SearchBrowseLoader.suggestOneChar(repository, trimmed)
            return@LaunchedEffect
        }
        searchLoading = true
        results = null
        val searchFor = trimmed
        runCatching {
            val response = repository.search(searchFor, limit = 20)
            if (query.trim() == searchFor) {
                results = response
                suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
                historyStore.add(searchFor)
            }
        }.onFailure {
            if (query.trim() == searchFor) {
                results = SearchResponse()
                suggestions = emptyList()
            }
        }
        if (query.trim() == searchFor) searchLoading = false
    }

    val trimmedQuery = query.trim()
    val showResults = trimmedQuery.length >= 2
    val showSuggestions = trimmedQuery.length == 1
    val showRecents = trimmedQuery.isEmpty() && searchFocused

    Column(Modifier.fillMaxSize()) {
        TabScreenHeader("Search") {
            AccountMenuButton(onAccountNavigate)
        }
        SearchField(
            query,
            { query = it },
            "What do you want to listen to?",
            modifier = Modifier.padding(horizontal = 16.dp),
            onFocusChanged = { searchFocused = it },
        )

        when {
            showResults -> when {
                searchLoading && results == null -> LoadingBox(Modifier.weight(1f))
                else -> SearchResultsList(
                    repository = repository,
                    results = results ?: SearchResponse(),
                    query = trimmedQuery,
                remoteOk = remoteOk,
                favoritePaths = favoritePaths,
                onPlay = onPlay,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                snackbarHostState = snackbarHostState,
                onFavoriteToggle = { path, hit, starred ->
                    scope.launch {
                        if (starred) repository.removeFavorite(path)
                        else repository.addFavorite(path, hit.title, hit.artist, hit.album)
                        favoritePaths = repository.favorites().map { it.path }.toSet()
                    }
                },
                )
            }
            showSuggestions -> {
                if (suggestions.isEmpty()) {
                    LoadingBox(Modifier.weight(1f))
                } else {
                    SearchSuggestionsList(
                        suggestions = suggestions,
                        repository = repository,
                        onSuggestionClick = { suggestion ->
                            SearchBrowseLoader.playTargetFor(suggestion)?.let { target ->
                                when (target) {
                                    is PlayTarget.Artist -> onOpenArtist(target.name)
                                    is PlayTarget.Album -> onOpenAlbum(target.name, target.artist)
                                    else -> onPlay(target)
                                }
                            } ?: run {
                                query = suggestion.title
                            }
                            scope.launch { historyStore.add(suggestion.title) }
                        },
                    )
                }
            }
            showRecents -> SearchRecentQueriesSection(
                queries = recentQueries,
                onQueryClick = { query = it },
                onRemove = { q -> scope.launch { historyStore.remove(q) } },
            )
            browseView == SearchView.NewReleases -> SearchNewReleasesList(
                albums = browseFeed?.newReleases.orEmpty(),
                repository = repository,
                onAlbumClick = { album -> onOpenAlbum(album.name, album.artist) },
                onBack = { browseView = SearchView.Browse },
            )
            browseLoading && browseFeed == null -> LoadingBox(Modifier.weight(1f))
            else -> BockLazyColumn(Modifier.weight(1f)) {
                browseFeed?.pickedForYou?.let { picked ->
                    item {
                        SearchPickedForYouSection(
                            cards = picked,
                            repository = repository,
                            onCardClick = { card -> onPlay(card.playTarget) },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
                item {
                    SearchBrowseAllSection(
                        newReleases = browseFeed?.newReleases.orEmpty(),
                        genres = browseFeed?.genres.orEmpty(),
                        repository = repository,
                        onNewReleasesClick = { browseView = SearchView.NewReleases },
                        onGenreClick = { genre -> onOpenGenre(genre.name) },
                        onGenreLongClick = { genre ->
                            scope.launch {
                                val seedArtist = runCatching {
                                    repository.songs(page = 1, search = genre.name, limit = 8)
                                        .items.mapNotNull { it.artist?.takeIf { a -> a.isNotBlank() } }
                                        .firstOrNull()
                                }.getOrNull() ?: genre.name
                                onPlay(
                                    PlayTarget.Radio(
                                        displayTitle = "${genre.name} Radio",
                                        seedKind = PlayTarget.RadioSeedKind.Genre,
                                        name = seedArtist,
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    repository: BockMediaRepository,
    results: SearchResponse,
    query: String,
    remoteOk: Boolean,
    favoritePaths: Set<String>,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    onFavoriteToggle: (String, SearchHit, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addToPlaylist by remember { mutableStateOf<Pair<String, String>?>(null) }

    addToPlaylist?.let { (path, title) ->
        AddToPlaylistSheet(
            repository = repository,
            trackPath = path,
            trackTitle = title,
            onDismiss = { addToPlaylist = null },
            onAdded = { msg ->
                scope.launch { snackbarHostState?.showSnackbar(msg) }
            },
        )
    }

    BockLazyColumn {
        results.playlists.takeIf { it.isNotEmpty() }?.let { list ->
            item { SearchSectionHeader("Playlists") }
            items(list, key = { "pl-${it.id}" }) { hit ->
                val target = PlayTarget.Playlist(hit.id ?: "", hit.name ?: "")
                SearchHitRow(
                    repository = repository,
                    kind = SearchSuggestionKind.Playlist,
                    hit = hit,
                    title = hit.name ?: "",
                    subtitle = null,
                    onClick = { onPlay(target) },
                    trailing = {
                        PlayDownloadActions(playTarget = target, remoteOk = remoteOk, onPlay = { onPlay(target) })
                    },
                )
            }
        }
        results.artists.takeIf { it.isNotEmpty() }?.let { list ->
            item { SearchSectionHeader("Artists") }
            items(list, key = { "ar-${it.name}" }) { hit ->
                val name = hit.name ?: ""
                val target = PlayTarget.Artist(name)
                SearchHitRow(
                    repository = repository,
                    kind = SearchSuggestionKind.Artist,
                    hit = hit,
                    title = name,
                    subtitle = null,
                    onClick = { onOpenArtist(name) },
                    trailing = {
                        PlayDownloadActions(playTarget = target, remoteOk = remoteOk, onPlay = { onPlay(target) })
                    },
                )
            }
        }
        results.albums.takeIf { it.isNotEmpty() }?.let { list ->
            item { SearchSectionHeader("Albums") }
            items(list, key = { "al-${it.name}-${it.artist}" }) { hit ->
                val target = PlayTarget.Album(hit.name ?: "", hit.artist)
                SearchHitRow(
                    repository = repository,
                    kind = SearchSuggestionKind.Album,
                    hit = hit,
                    title = hit.name ?: "",
                    subtitle = hit.artist,
                    onClick = { hit.name?.let { onOpenAlbum(it, hit.artist) } },
                    trailing = {
                        PlayDownloadActions(playTarget = target, remoteOk = remoteOk, onPlay = { onPlay(target) })
                    },
                )
            }
        }
        results.songs.takeIf { it.isNotEmpty() }?.let { list ->
            item { SearchSectionHeader("Songs") }
            items(list, key = { "so-${it.path}" }) { hit ->
                val path = hit.path.orEmpty()
                val starred = path in favoritePaths
                val target = PlayTarget.Song(path, hit.title ?: "")
                val canPlay = remoteOk || OfflineDownloadManager.isDownloaded(target)
                SearchHitRow(
                    repository = repository,
                    kind = SearchSuggestionKind.Song,
                    hit = hit,
                    title = hit.title ?: hit.name ?: "",
                    subtitle = hit.artist,
                    onClick = {
                        if (remoteOk) onPlay(target)
                        else scope.launch {
                            val err = PhonePlayback.playLocally(context, target)
                            err?.let { snackbarHostState?.showSnackbar(it) }
                        }
                    },
                    trailing = {
                        PlayDownloadActions(
                            playTarget = target,
                            remoteOk = canPlay,
                            onPlay = {
                                if (remoteOk) onPlay(target)
                                else scope.launch {
                                    val err = PhonePlayback.playLocally(context, target)
                                    err?.let { snackbarHostState?.showSnackbar(it) }
                                }
                            },
                            leading = {
                                IconButton(onClick = { addToPlaylist = path to (hit.title ?: hit.name ?: "Track") }) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = "Add to playlist")
                                }
                                IconButton(onClick = { onFavoriteToggle(path, hit, starred) }) {
                                    Icon(
                                        if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = "Favorite",
                                        tint = if (starred) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }
        if (results.playlists.isEmpty() && results.artists.isEmpty() &&
            results.albums.isEmpty() && results.songs.isEmpty()
        ) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp)) {
                    Text("No results for \"$query\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchHitRow(
    repository: BockMediaRepository,
    kind: SearchSuggestionKind,
    hit: SearchHit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val variantKey = hit.id ?: hit.path ?: title
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = hit.path,
        playlistId = if (kind == SearchSuggestionKind.Playlist) hit.id else null,
        artistName = if (kind == SearchSuggestionKind.Artist) title else null,
        albumName = if (kind == SearchSuggestionKind.Album) title else null,
        albumArtist = hit.artist,
        variantKey = variantKey,
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            BockArtwork(
                model = artUrl,
                title = title,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(if (kind == SearchSuggestionKind.Artist) 24.dp else 6.dp),
                fallbackFontSize = 16.sp,
            )
        },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}
