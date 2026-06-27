package com.bockmedia.console.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SearchPin
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import com.bockmedia.console.local.SearchHistoryStore
import com.bockmedia.console.local.SearchRecentSelection
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.discovery.AcquireIdeasDialog
import com.bockmedia.console.ui.discovery.MixMuseDialog
import com.bockmedia.console.ui.discovery.DiscoverySeed
import com.bockmedia.console.ui.discovery.DiscoverySeedKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onAccountNavigate: (String) -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
) {
    val context = LocalContext.current
    val historyStore = remember { SearchHistoryStore(context) }
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf(SearchResultsSessionCache.query) }
    var results by remember { mutableStateOf(SearchResultsSessionCache.results) }
    var searchLoading by remember { mutableStateOf(false) }
    var suggestions by remember {
        mutableStateOf(
            SearchResultsSessionCache.suggestions.ifEmpty {
                SearchResultsSessionCache.results?.let { SearchBrowseLoader.suggestionsFromResponse(it) }
                    ?: emptyList()
            },
        )
    }
    var recentSelections by remember { mutableStateOf<List<SearchRecentSelection>>(emptyList()) }
    var songRatings by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchPins by remember { mutableStateOf<List<SearchPin>>(emptyList()) }
    var showPinEditor by remember { mutableStateOf(false) }
    var rankingKind by remember { mutableStateOf<SearchRankingKind?>(null) }
    var showSonicAdventure by remember { mutableStateOf(false) }
    var showSonicSage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            songRatings = repository.ratedSongMap()
            searchPins = repository.searchPins()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching { searchPins = repository.searchPins() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(historyStore) {
        historyStore.selections.collectLatest { recentSelections = it }
    }

    fun recordSelection(selection: SearchRecentSelection) {
        scope.launch { historyStore.addSelection(selection) }
    }

    LaunchedEffect(query) {
        delay(250)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            results = null
            searchLoading = false
            SearchResultsSessionCache.clear()
            return@LaunchedEffect
        }
        if (trimmed.length < 2) {
            results = null
            searchLoading = false
            suggestions = SearchBrowseLoader.suggestOneChar(repository, trimmed)
            SearchResultsSessionCache.saveSnapshot(trimmed, null, suggestions, null, true)
            return@LaunchedEffect
        }
        if (SearchResultsSessionCache.hasFreshResults(trimmed, null, true)) {
            results = SearchResultsSessionCache.results
            suggestions = SearchResultsSessionCache.suggestions
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        results = null
        val searchFor = trimmed
        runCatching {
            val response = repository.search(
                searchFor,
                limit = 30,
                preview = 5,
                source = null,
            )
            if (query.trim() == searchFor) {
                results = response
                suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
                SearchResultsSessionCache.saveSnapshot(
                    searchFor, response, suggestions, null, true,
                )
            }
        }.onFailure {
            if (query.trim() == searchFor) {
                results = SearchResponse()
                suggestions = emptyList()
                SearchResultsSessionCache.saveSnapshot(
                    searchFor, SearchResponse(), emptyList(), null, true,
                )
            }
        }
        if (query.trim() == searchFor) searchLoading = false
    }

    LaunchedEffect(query, results, suggestions) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            SearchResultsSessionCache.saveSnapshot(
                trimmed, results, suggestions, null, true,
            )
        }
    }

    if (showPinEditor) {
        SearchPinsEditorSheet(
            repository = repository,
            initialPins = searchPins,
            onDismiss = { showPinEditor = false },
            onSaved = { searchPins = it },
        )
    }
    if (showSonicAdventure) {
        SearchSonicAdventureScreen(
            repository = repository,
            onBack = { showSonicAdventure = false },
            onOpenAlbum = { album, artist ->
                recordSelection(SearchRecentSelection.fromAlbum(album, artist))
                showSonicAdventure = false
                onOpenAlbum(album, artist)
            },
            onOpenGenre = { name ->
                recordSelection(SearchRecentSelection.fromGenre(name))
                showSonicAdventure = false
                onOpenGenre(name)
            },
        )
        return
    }
    if (showSonicSage) {
        MixMuseDialog(
            repository = repository,
            seed = DiscoverySeed(
                kind = DiscoverySeedKind.playlist,
                title = "My library",
            ),
            onDismiss = { showSonicSage = false },
            onPlaylistCreated = { _, _ -> },
        )
    }

    rankingKind?.let { kind ->
        SearchTopRankingScreen(
            kind = kind,
            repository = repository,
            remoteOk = remoteOk,
            onBack = { rankingKind = null },
            onOpenArtist = { name ->
                recordSelection(SearchRecentSelection.fromArtist(name))
                onOpenArtist(name)
            },
            onOpenAlbum = { album, artist ->
                recordSelection(SearchRecentSelection.fromAlbum(album, artist))
                onOpenAlbum(album, artist)
            },
            onPlay = onPlay,
        )
        return
    }

    val trimmedQuery = query.trim()
    val showResults = trimmedQuery.length >= 2
    val showSuggestions = trimmedQuery.length == 1

    Column(Modifier.fillMaxSize()) {
        TabScreenHeader("Search") {
            AccountMenuButton(onAccountNavigate)
        }
        SearchField(
            query,
            { query = it },
            "Search…",
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .testTag(BockTestTags.SEARCH_FIELD),
        )
        when {
            showResults -> when {
                searchLoading && results == null -> LoadingBox(Modifier.weight(1f))
                else -> SearchResultsList(
                    repository = repository,
                    results = results ?: SearchResponse(),
                    query = trimmedQuery,
                    searchSource = null,
                    remoteOk = remoteOk,
                    songRatings = songRatings,
                    onPlay = onPlay,
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                    onOpenGenre = onOpenGenre,
                    onOpenPlaylist = { id ->
                        scope.launch {
                            runCatching { repository.playlists(search = "", limit = 500) }
                                .getOrNull()?.items?.find { it.id == id }?.let { pl ->
                                    recordSelection(SearchRecentSelection.fromPlaylist(id, pl.name))
                                }
                        }
                        onOpenPlaylist(id)
                    },
                    snackbarHostState = snackbarHostState,
                    onRatingChange = { path, hit, stars ->
                        scope.launch {
                            runCatching {
                                repository.setRating(
                                    kind = com.bockmedia.console.ui.components.RatingKind.Song,
                                    id = path,
                                    stars = stars,
                                    title = hit.title,
                                    artist = hit.artist,
                                    album = hit.album,
                                )
                            }
                            songRatings = repository.ratedSongMap()
                        }
                    },
                    onRecordSelection = ::recordSelection,
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
                            SearchRecentSelection.fromSuggestion(suggestion)?.let(::recordSelection)
                            if (suggestion.kind == SearchSuggestionKind.Playlist && suggestion.id != null) {
                                onOpenPlaylist(suggestion.id)
                            } else {
                                SearchBrowseLoader.playTargetFor(suggestion)?.let { target ->
                                    when (target) {
                                        is PlayTarget.Artist -> onOpenArtist(target.name)
                                        is PlayTarget.Album -> onOpenAlbum(target.name, target.artist)
                                        else -> onPlay(target)
                                    }
                                } ?: run { query = suggestion.title }
                            }
                        },
                    )
                }
            }
            else -> BockLazyColumn(Modifier.weight(1f)) {
                item {
                    SearchPlexampBrowse(
                        repository = repository,
                        recentSelections = recentSelections,
                        customPins = searchPins,
                        onAuralFixation = { rankingKind = it },
                        onSonicAdventure = { showSonicAdventure = true },
                        onSonicSage = { showSonicSage = true },
                        onEditShortcuts = { showPinEditor = true },
                        onPinClick = { pin ->
                            val title = pin.title ?: pin.name ?: "Shortcut"
                            when (pin.kind.lowercase()) {
                                "playlist" -> pin.id?.let(onOpenPlaylist)
                                "genre" -> onOpenGenre(pin.name ?: title)
                                "artist" -> onOpenArtist(pin.name ?: title)
                                "album" -> onOpenAlbum(pin.name ?: title, pin.artist)
                                "radio", "mix" -> onPlay(
                                    PlayTarget.Radio(
                                        title,
                                        PlayTarget.RadioSeedKind.Artist,
                                        pin.name ?: title,
                                        pin.path,
                                    ),
                                )
                                else -> pin.path?.let { onPlay(PlayTarget.Song(it, title)) }
                            }
                        },
                        onRecentClick = { item ->
                            when (item.kind) {
                                "artist" -> onOpenArtist(item.title)
                                "album" -> onOpenAlbum(item.title, item.artist)
                                "playlist" -> item.id?.let(onOpenPlaylist)
                                "genre" -> onOpenGenre(item.title)
                                "song" -> item.path?.let { onPlay(PlayTarget.Song(it, item.title)) }
                            }
                        },
                        onRecentRemove = { item ->
                            scope.launch { historyStore.removeSelection(item) }
                        },
                        onClearRecent = {
                            scope.launch { historyStore.clearSelections() }
                        },
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                    )
                }
            }
        }
    }
}
