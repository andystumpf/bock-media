package com.bockmedia.console.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.SearchSuggestion
import com.bockmedia.console.domain.model.SearchSuggestionKind
import com.bockmedia.console.local.SearchRecentSelection
import com.bockmedia.console.ui.components.AccountMenuButton
import com.bockmedia.console.ui.listen.ListenAgentMicButton
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.testing.BockTestTags

private val SpotifyMuted = Color(0xFFB3B3B3)

/** Single persistent search field — browse vs active chrome only; avoids keyboard dismiss on first keystroke. */
@Composable
fun SpotifySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAccountNavigate: (String) -> Unit,
    onOpenListenAgent: () -> Unit = {},
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSearch: () -> Unit = {},
) {
    val isActive = query.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(bottom = 8.dp),
    ) {
        if (!isActive) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Search",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                ListenAgentMicButton(onClick = onOpenListenAgent)
                AccountMenuButton(onAccountNavigate)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isActive) 4.dp else 16.dp, vertical = if (isActive) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isActive) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (!isActive) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF282828))
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = if (isActive) 0.dp else 12.dp, vertical = if (isActive) 0.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isActive) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = SpotifyMuted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(BockTestTags.SEARCH_FIELD),
                    decorationBox = { inner ->
                        Box {
                            if (!isActive && query.isEmpty()) {
                                Text(
                                    "What do you want to listen to?",
                                    color = SpotifyMuted,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(if (isActive) 48.dp else 24.dp),
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = if (isActive) Color.White else SpotifyMuted,
                            modifier = Modifier.size(if (isActive) 24.dp else 20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** @deprecated Use [SpotifySearchBar] — kept for previews/tests. */
@Composable
fun SpotifySearchBrowseHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onAccountNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(bottom = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            AccountMenuButton(onAccountNavigate)
        }
        SpotifySearchPillField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = "What do you want to listen to?",
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(BockTestTags.SEARCH_FIELD),
        )
    }
}

/** Active search: back + inline field + clear (Spotify typing state). */
@Composable
fun SpotifySearchActiveBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier
                .weight(1f)
                .testTag(BockTestTags.SEARCH_FIELD),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.White)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun SpotifySearchPillField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF282828))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = SpotifyMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(placeholder, color = SpotifyMuted, style = MaterialTheme.typography.bodyLarge)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear", tint = SpotifyMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Unified Spotify-style feed: text autocomplete rows + artwork result rows. */
@Composable
fun SpotifySearchFeed(
    query: String,
    activeQuery: String,
    results: SearchResponse?,
    suggestions: List<SearchSuggestion>,
    loading: Boolean,
    errorMessage: String?,
    repository: BockMediaRepository,
    songRatings: Map<String, Int>,
    remoteOk: Boolean,
    onFillQuery: (String) -> Unit,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (String, String?) -> Unit,
    onRecordSelection: (SearchRecentSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trimmed = query.trim()
    val settled = activeQuery.trim()
    val context = androidx.compose.ui.platform.LocalContext.current
    val autocomplete = remember(trimmed, results, suggestions) {
        buildAutocompleteCompletions(trimmed, results, suggestions)
    }
    val flatItems = remember(results) { results?.let { flatSpotifyResults(it) }.orEmpty() }

    LaunchedEffect(flatItems) {
        if (flatItems.isNotEmpty()) {
            SearchArtPrefetch.warm(context, repository, flatItems)
        }
    }

    if (loading && results == null && autocomplete.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LoadingBox()
        }
        return
    }

    BockLazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(BockTestTags.SEARCH_RESULTS),
    ) {
        if (loading) {
            item(key = "loading") {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (!errorMessage.isNullOrBlank()) {
            item(key = "error") {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        items(autocomplete.size, key = { "ac-$it-${autocomplete[it]}" }) { idx ->
            SpotifyAutocompleteRow(
                query = trimmed,
                completion = autocomplete[idx],
                onFill = { onFillQuery(autocomplete[idx]) },
                onSelect = { onFillQuery(autocomplete[idx]) },
            )
        }
        items(flatItems.size, key = { flatItems[it].key }) { idx ->
            SpotifyResultRow(
                item = flatItems[idx],
                repository = repository,
                onPlay = onPlay,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onOpenGenre = onOpenGenre,
                onOpenPlaylist = onOpenPlaylist,
                onRecordSelection = onRecordSelection,
            )
        }
        if (!loading && settled == trimmed && errorMessage.isNullOrBlank() &&
            autocomplete.isEmpty() && flatItems.isEmpty() && trimmed.length >= 1
        ) {
            item(key = "empty") {
                Text(
                    if (trimmed.length < 2) "Keep typing to search" else "No results for \"$trimmed\"",
                    color = SpotifyMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun SpotifyAutocompleteRow(
    query: String,
    completion: String,
    onFill: () -> Unit,
    onSelect: () -> Unit,
) {
    val prefixLen = query.length.coerceAtMost(completion.length)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = SpotifyMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            buildAnnotatedString {
                if (prefixLen > 0) {
                    withStyle(SpanStyle(color = SpotifyMuted)) {
                        append(completion.substring(0, prefixLen))
                    }
                }
                if (prefixLen < completion.length) {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                        append(completion.substring(prefixLen))
                    }
                }
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        IconButton(onClick = onFill, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.NorthEast, contentDescription = "Fill search", tint = SpotifyMuted, modifier = Modifier.size(18.dp))
        }
    }
}

internal sealed class SpotifyFlatItem {
    abstract val key: String
    data class Song(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "song-${hit.path ?: hit.title}"
    }
    data class Playlist(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "pl-${hit.id}"
    }
    data class SmartPlaylist(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "smart-${hit.id}"
    }
    data class Artist(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "art-${hit.name}"
    }
    data class Album(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "alb-${hit.name}-${hit.artist}"
    }
    data class Genre(val hit: SearchHit) : SpotifyFlatItem() {
        override val key: String = "gen-${hit.name}"
    }
}

@Composable
private fun SpotifyResultRow(
    item: SpotifyFlatItem,
    repository: BockMediaRepository,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenPlaylist: (String, String?) -> Unit,
    onRecordSelection: (SearchRecentSelection) -> Unit,
) {
    val hit: SearchHit
    val title: String
    val subtitle: String
    val kind: SearchSuggestionKind
    val onClick: () -> Unit

    when (item) {
        is SpotifyFlatItem.Song -> {
            hit = item.hit
            title = hit.title ?: hit.name ?: ""
            subtitle = hit.artist?.let { "Song · $it" } ?: "Song"
            kind = SearchSuggestionKind.Song
            onClick = {
                hit.path?.let { path ->
                    onRecordSelection(SearchRecentSelection.fromSong(title, hit.artist, path))
                    onPlay(PlayTarget.Song(path, title))
                }
            }
        }
        is SpotifyFlatItem.Playlist -> {
            hit = item.hit
            val id = hit.id ?: return
            title = hit.name ?: "Playlist"
            subtitle = "Playlist"
            kind = SearchSuggestionKind.Playlist
            onClick = {
                onRecordSelection(SearchRecentSelection.fromPlaylist(id, title))
                onOpenPlaylist(id, title)
            }
        }
        is SpotifyFlatItem.SmartPlaylist -> {
            hit = item.hit
            val id = hit.id ?: return
            title = hit.name ?: "Smart playlist"
            subtitle = "Smart playlist"
            kind = SearchSuggestionKind.Playlist
            onClick = {
                onRecordSelection(SearchRecentSelection.fromPlaylist(id, title))
                onOpenPlaylist(id, title)
            }
        }
        is SpotifyFlatItem.Artist -> {
            hit = item.hit
            title = hit.name ?: return
            subtitle = "Artist"
            kind = SearchSuggestionKind.Artist
            onClick = {
                onRecordSelection(SearchRecentSelection.fromArtist(title, hit.albums))
                onOpenArtist(title)
            }
        }
        is SpotifyFlatItem.Album -> {
            hit = item.hit
            title = hit.name ?: return
            subtitle = hit.artist?.let { "Album · $it" } ?: "Album"
            kind = SearchSuggestionKind.Album
            onClick = {
                onRecordSelection(SearchRecentSelection.fromAlbum(title, hit.artist))
                onOpenAlbum(title, hit.artist)
            }
        }
        is SpotifyFlatItem.Genre -> {
            hit = item.hit
            title = hit.name ?: return
            subtitle = "Genre"
            kind = SearchSuggestionKind.Album
            onClick = {
                onRecordSelection(SearchRecentSelection.fromGenre(title))
                onOpenGenre(title)
            }
        }
    }

    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = hit.path,
        playlistId = if (kind == SearchSuggestionKind.Playlist) hit.id else null,
        artistName = if (kind == SearchSuggestionKind.Artist) title else null,
        albumName = if (kind == SearchSuggestionKind.Album) title else hit.album,
        albumArtist = hit.artist,
        variantKey = hit.id ?: hit.path ?: title,
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
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 2)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SpotifyMuted, maxLines = 1)
        }
        Icon(Icons.Default.MoreVert, contentDescription = null, tint = SpotifyMuted, modifier = Modifier.size(20.dp))
    }
}

private fun buildAutocompleteCompletions(
    query: String,
    results: SearchResponse?,
    suggestions: List<SearchSuggestion>,
): List<String> {
    if (query.length < 1) return emptyList()
    val lower = query.lowercase()
    val out = linkedSetOf<String>()
    fun consider(raw: String?) {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (t.lowercase().startsWith(lower)) out.add(t)
    }
    suggestions.forEach { consider(it.title) }
    results?.songs?.forEach { consider(it.title ?: it.name) }
    results?.artists?.forEach { consider(it.name) }
    results?.albums?.forEach { consider(it.name) }
    results?.playlists?.forEach { consider(it.name) }
    results?.smartPlaylists?.forEach { consider(it.name) }
    return out.take(6).toList()
}

internal fun flatSpotifyResults(response: SearchResponse): List<SpotifyFlatItem> {
    val preview = response.preview.coerceAtLeast(5)
    return buildList {
        response.songs.take(preview).forEach { add(SpotifyFlatItem.Song(it)) }
        response.playlists.take(preview).forEach { add(SpotifyFlatItem.Playlist(it)) }
        response.smartPlaylists.take(preview).forEach { add(SpotifyFlatItem.SmartPlaylist(it)) }
        response.artists.take(preview).forEach { add(SpotifyFlatItem.Artist(it)) }
        response.albums.take(preview).forEach { add(SpotifyFlatItem.Album(it)) }
        response.genres.take(preview).forEach { add(SpotifyFlatItem.Genre(it)) }
    }
}
