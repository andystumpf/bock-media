package com.bockmedia.console.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SearchPin
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.*
import com.bockmedia.console.local.SearchHistoryStore
import com.bockmedia.console.local.SearchRecentSelection
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.discovery.MixMuseDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val MINI_PLAYER_CLEARANCE = 80.dp

@Composable
fun SearchScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onOpenNewPlaylist: (String) -> Unit = onOpenPlaylist,
    onAccountNavigate: (String) -> Unit = {},
    onOpenListenAgent: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    uitestQuery: String = "",
    uitestQueryNonce: Int = 0,
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val historyStore = remember { SearchHistoryStore(context) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResponse?>(null) }
    var activeQuery by remember { mutableStateOf("") }
    var searchLoading by remember { mutableStateOf(false) }
    var displayedError by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<SearchSuggestion>>(emptyList()) }
    var recentSelections by remember { mutableStateOf<List<SearchRecentSelection>>(emptyList()) }
    var songRatings by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var searchPins by remember { mutableStateOf<List<SearchPin>>(emptyList()) }
    var showPinEditor by remember { mutableStateOf(false) }
    var rankingKind by remember { mutableStateOf<SearchRankingKind?>(null) }
    var showSonicAdventure by remember { mutableStateOf(false) }
    var showSonicSage by remember { mutableStateOf(false) }
    var searchSource by remember { mutableStateOf<String?>(null) }
    var resultFilter by remember { mutableStateOf(SearchResultFilter.All) }
    val browseFeed = remember { SearchBrowseSessionCache.getIfFresh() }

    LaunchedEffect(Unit) {
        TabWarmCoordinator.warmSearchBrowse(scope, repository)
        val allLibs = prefs.isSearchAllLibrariesSync()
        searchSource = if (allLibs) null else prefs.getSearchSourcePathSync()
        val cachedQuery = SearchResultsSessionCache.query.trim()
        if (cachedQuery.isNotEmpty() && SearchResultsSessionCache.results != null) {
            query = SearchResultsSessionCache.query
            results = SearchResultsSessionCache.results
            suggestions = SearchResultsSessionCache.suggestions
            activeQuery = cachedQuery
        }
        launch { runCatching { searchPins = repository.searchPins() } }
    }

    LaunchedEffect(uitestQueryNonce) {
        if (uitestQuery.isNotBlank()) {
            query = uitestQuery
        }
    }

    LaunchedEffect(results) {
        if (songRatings.isEmpty() && results != null && results!!.songs.isNotEmpty()) {
            runCatching { songRatings = repository.ratedSongMap() }
        }
    }

    LaunchedEffect(historyStore) {
        historyStore.selections.collectLatest { recentSelections = it }
    }

    LaunchedEffect(displayedError) {
        val msg = displayedError ?: return@LaunchedEffect
        snackbarHostState?.showSnackbar(msg)
    }

    fun recordSelection(selection: SearchRecentSelection) {
        scope.launch { historyStore.addSelection(selection) }
    }

    fun applySearchFailure(trimmed: String, err: Throwable) {
        if (query.trim() != trimmed) return
        displayedError = UnifiedSearchCoordinator.userErrorMessage(err, remoteOk)
        results = null
        suggestions = emptyList()
        searchLoading = false
        activeQuery = trimmed
    }

    fun applySearchSuccess(trimmed: String, response: SearchResponse) {
        if (query.trim() != trimmed) return
        results = response
        suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
        searchLoading = false
        displayedError = null
        activeQuery = trimmed
        UnifiedSearchCoordinator.cachePut(trimmed, response)
        SearchResultsSessionCache.saveSnapshot(trimmed, response, suggestions, searchSource, true)
    }

    LaunchedEffect(query, searchSource) {
        delay(UnifiedSearchCoordinator.DEBOUNCE_MS)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList()
            results = null
            searchLoading = false
            displayedError = null
            activeQuery = ""
            SearchResultsSessionCache.clear()
            SearchQueryCache.invalidate()
            return@LaunchedEffect
        }
        if (SearchResultsSessionCache.query != trimmed) {
            SearchResultsSessionCache.expandedSections.clear()
            SearchResultsSessionCache.expandedData.clear()
        }

        activeQuery = trimmed
        displayedError = null

        UnifiedSearchCoordinator.resolveCached(trimmed)?.let { cached ->
            if (cached.fromCache) {
                applySearchSuccess(trimmed, cached.response)
                return@LaunchedEffect
            }
            results = cached.response
            suggestions = SearchBrowseLoader.suggestionsFromResponse(cached.response)
        } ?: run {
            if (SearchResultsSessionCache.hasFreshResults(trimmed, searchSource, true)) {
                results = SearchResultsSessionCache.results
                suggestions = SearchResultsSessionCache.suggestions
                searchLoading = false
                activeQuery = trimmed
                return@LaunchedEffect
            }
            results = null
        }

        searchLoading = true
        runCatching { UnifiedSearchCoordinator.fetch(repository, trimmed, searchSource) }
            .onSuccess { full ->
                if (query.trim() != trimmed) return@onSuccess
                val prior = results
                when {
                    full.hasAnyResults() -> applySearchSuccess(trimmed, full)
                    prior != null && prior.hasAnyResults() && trimmed.length >= 2 -> {
                        val narrowed = prior.narrowToQuery(trimmed)
                        if (narrowed.hasAnyResults()) applySearchSuccess(trimmed, narrowed)
                        else applySearchSuccess(trimmed, full)
                    }
                    else -> applySearchSuccess(trimmed, full)
                }
            }
            .onFailure { applySearchFailure(trimmed, it) }
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
            seed = null,
            title = "Sonic Sage",
            defaultName = "Sonic Sage",
            onDismiss = { showSonicSage = false },
            onPlaylistCreated = { id, _ ->
                showSonicSage = false
                onOpenNewPlaylist(id)
            },
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
    val isSearching = trimmedQuery.isNotEmpty()

    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isEmpty()) resultFilter = SearchResultFilter.All
    }

    Column(
        Modifier.fillMaxSize(),
    ) {
        SpotifySearchBar(
            query = query,
            onQueryChange = { query = it },
            onBack = {
                query = ""
                focusManager.clearFocus()
            },
            onAccountNavigate = onAccountNavigate,
            onOpenListenAgent = onOpenListenAgent,
            focusRequester = focusRequester,
            onSearch = { focusManager.clearFocus() },
        )
        if (isSearching) {
            SearchResultFilterChips(
                selected = resultFilter,
                onSelect = { resultFilter = it },
            )
            Box(
                Modifier
                    .weight(1f)
                    .padding(bottom = MINI_PLAYER_CLEARANCE),
            ) {
                if (searchLoading && results == null) {
                    LoadingBox(Modifier.fillMaxSize())
                } else if (results != null) {
                    SearchResultsList(
                        repository = repository,
                        results = results!!,
                        query = activeQuery.ifBlank { trimmedQuery },
                        searchSource = searchSource,
                        remoteOk = remoteOk,
                        songRatings = songRatings,
                        onPlay = onPlay,
                        onOpenArtist = onOpenArtist,
                        onOpenAlbum = onOpenAlbum,
                        onOpenGenre = onOpenGenre,
                        onOpenPlaylist = { id, name ->
                            recordSelection(SearchRecentSelection.fromPlaylist(id, name ?: "Playlist"))
                            onOpenPlaylist(id)
                        },
                        snackbarHostState = snackbarHostState,
                        onRatingChange = { path, hit, stars ->
                            scope.launch {
                                runCatching {
                                    repository.setRating(
                                        kind = RatingKind.Song,
                                        id = path,
                                        stars = stars,
                                        title = hit.title,
                                        artist = hit.artist,
                                        album = hit.album,
                                    )
                                    songRatings = songRatings + (path to stars)
                                }
                            }
                        },
                        onRecordSelection = ::recordSelection,
                        sectionFilter = resultFilter,
                    )
                } else if (!searchLoading) {
                    Text(
                        if (trimmedQuery.length < 2) "Keep typing to search" else "No results for \"$trimmedQuery\"",
                        modifier = Modifier.padding(32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (searchLoading && results != null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        } else {
            BockLazyColumn(
                Modifier
                    .weight(1f)
                    .padding(bottom = MINI_PLAYER_CLEARANCE),
            ) {
                browseFeed?.pickedForYou?.takeIf { it.isNotEmpty() }?.let { cards ->
                    item(key = "picked") {
                        SearchPickedForYouSection(
                            cards = cards,
                            repository = repository,
                            onCardClick = { card -> card.playTarget?.let(onPlay) },
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
                }
                browseFeed?.let { feed ->
                    if (feed.newReleases.isNotEmpty() || feed.genres.isNotEmpty()) {
                        item(key = "browse-all") {
                            SearchBrowseAllSection(
                                newReleases = feed.newReleases,
                                genres = feed.genres,
                                repository = repository,
                                onNewReleasesClick = { },
                                onGenreClick = { genre -> onOpenGenre(genre.name) },
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    }
                }
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
