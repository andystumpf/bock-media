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
) {
    val context = LocalContext.current
    val historyStore = remember { SearchHistoryStore(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResponse?>(null) }
    var suggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var browseFeed by remember { mutableStateOf<SearchBrowseFeed?>(null) }
    var browseLoading by remember { mutableStateOf(true) }
    var recentQueries by remember { mutableStateOf<List<String>>(emptyList()) }
    var favoritePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var browseView by remember { mutableStateOf(SearchView.Browse) }

    LaunchedEffect(Unit) {
        runCatching { favoritePaths = repository.favorites().map { it.path }.toSet() }
        browseLoading = true
        runCatching { browseFeed = SearchBrowseLoader.load(repository) }
        browseLoading = false
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
            return@LaunchedEffect
        }
        if (trimmed.length < 2) {
            results = null
            suggestions = SearchBrowseLoader.suggestOneChar(repository, trimmed)
            return@LaunchedEffect
        }
        runCatching {
            val response = repository.search(trimmed, limit = 20)
            results = response
            suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
            historyStore.add(trimmed)
        }.onFailure {
            results = null
            suggestions = emptyList()
        }
    }

    val showResults = query.trim().length >= 2 && results != null
    val showSuggestions = !showResults && query.isNotBlank()
    val showRecents = !showResults && !showSuggestions && searchFocused

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        SearchField(
            query,
            { query = it },
            "What do you want to listen to?",
            modifier = Modifier.padding(horizontal = 16.dp),
            onFocusChanged = { searchFocused = it },
        )
        Spacer(Modifier.height(8.dp))

        when {
            showResults -> SearchResultsList(
                repository = repository,
                results = results!!,
                query = query.trim(),
                remoteOk = remoteOk,
                favoritePaths = favoritePaths,
                onPlay = onPlay,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onFavoriteToggle = { path, hit, starred ->
                    scope.launch {
                        if (starred) repository.removeFavorite(path)
                        else repository.addFavorite(path, hit.title, hit.artist, hit.album)
                        favoritePaths = repository.favorites().map { it.path }.toSet()
                    }
                },
            )
            showSuggestions -> SearchSuggestionsList(
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
                        onGenreClick = { genre ->
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
    onFavoriteToggle: (String, SearchHit, Boolean) -> Unit,
) {
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
                SearchHitRow(
                    repository = repository,
                    kind = SearchSuggestionKind.Song,
                    hit = hit,
                    title = hit.title ?: hit.name ?: "",
                    subtitle = hit.artist,
                    onClick = { onPlay(target) },
                    trailing = {
                        PlayDownloadActions(
                            playTarget = target,
                            remoteOk = remoteOk,
                            onPlay = { onPlay(target) },
                            leading = {
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
