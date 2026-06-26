package com.bockmedia.console.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeCard
import com.bockmedia.console.domain.model.SearchSuggestion
import com.bockmedia.console.domain.model.SearchSuggestionKind
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.rememberArtworkUrl

private val TileShape = RoundedCornerShape(8.dp)

@Composable
fun SearchPickedForYouSection(
    cards: List<HomeCard>,
    repository: BockMediaRepository,
    onCardClick: (HomeCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(modifier) {
        Text(
            "Picked for you",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(cards, key = { it.id }) { card ->
                SearchPickedCard(card, repository, onCardClick)
            }
        }
    }
}

@Composable
private fun SearchPickedCard(
    card: HomeCard,
    repository: BockMediaRepository,
    onClick: (HomeCard) -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = card.title,
        artPath = card.artPath,
        playlistId = card.playlistId,
        variantKey = card.id,
    )
    Column(
        Modifier
            .width(124.dp)
            .clickable { onClick(card) },
    ) {
        BockArtwork(
            model = artUrl,
            title = card.title,
            modifier = Modifier
                .size(124.dp)
                .clip(TileShape),
            fallbackFontSize = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        card.subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SearchBrowseAllSection(
    newReleases: List<AlbumItem>,
    genres: List<GenreItem>,
    repository: BockMediaRepository,
    onNewReleasesClick: () -> Unit,
    onGenreClick: (GenreItem) -> Unit,
    onGenreLongClick: (GenreItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tiles = genres
    Column(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Browse all",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchNewReleasesTile(
                coverAlbum = newReleases.firstOrNull(),
                repository = repository,
                onClick = onNewReleasesClick,
                modifier = Modifier.weight(1f),
            )
            if (tiles.isNotEmpty()) {
                SearchGenreTile(
                    genre = tiles.first(),
                    repository = repository,
                    onClick = onGenreClick,
                    onLongClick = onGenreLongClick,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        tiles.drop(1).chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { genre ->
                    SearchGenreTile(
                        genre = genre,
                        repository = repository,
                        onClick = onGenreClick,
                        onLongClick = onGenreLongClick,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SearchNewReleasesTile(
    coverAlbum: AlbumItem?,
    repository: BockMediaRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = coverAlbum?.name ?: "New Releases",
        artPath = coverAlbum?.artPath,
        albumName = coverAlbum?.name,
        albumArtist = coverAlbum?.artist,
    )
    SearchBrowseTile(
        title = "New Releases",
        artUrl = artUrl,
        fallbackTitle = coverAlbum?.name ?: "New",
        onClick = onClick,
        modifier = modifier,
        leadingIcon = {
            Icon(
                Icons.Default.NewReleases,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchGenreTile(
    genre: GenreItem,
    repository: BockMediaRepository,
    onClick: (GenreItem) -> Unit,
    onLongClick: (GenreItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = genre.name,
        artPath = genre.artPath,
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(TileShape)
            .combinedClickable(
                onClick = { onClick(genre) },
                onLongClick = { onLongClick(genre) },
            ),
    ) {
        BockArtwork(
            model = artUrl,
            title = genre.name,
            modifier = Modifier.fillMaxSize(),
            shape = TileShape,
            fallbackFontSize = 22.sp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                    ),
                ),
        )
        Text(
            genre.name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchBrowseTile(
    title: String,
    artUrl: String?,
    fallbackTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(TileShape)
            .clickable(onClick = onClick),
    ) {
        BockArtwork(
            model = artUrl,
            title = fallbackTitle,
            modifier = Modifier.fillMaxSize(),
            shape = TileShape,
            fallbackFontSize = 22.sp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.2f)),
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke()
            if (leadingIcon != null) Spacer(Modifier.width(6.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun SearchNewReleasesList(
    albums: List<AlbumItem>,
    repository: BockMediaRepository,
    onAlbumClick: (AlbumItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Browse all") }
            Text(
                "New Releases",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }
        albums.forEach { album ->
            SearchAlbumRow(album, repository, onAlbumClick)
        }
    }
}

@Composable
fun SearchAlbumRow(
    album: AlbumItem,
    repository: BockMediaRepository,
    onClick: (AlbumItem) -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = album.name,
        artPath = album.artPath,
        albumName = album.name,
        albumArtist = album.artist,
    )
    ListItem(
        headlineContent = { Text(album.name) },
        supportingContent = {
            Text(listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "))
        },
        leadingContent = {
            BockArtwork(
                model = artUrl,
                title = album.name,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp),
                fallbackFontSize = 16.sp,
            )
        },
        modifier = Modifier.clickable { onClick(album) },
    )
}

@Composable
fun SearchRecentQueriesSection(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queries.isEmpty()) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No recent searches", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(modifier) {
        Text(
            "Recent searches",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        queries.forEach { q ->
            ListItem(
                headlineContent = { Text(q) },
                leadingContent = {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(q) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                },
                modifier = Modifier.clickable { onQueryClick(q) },
            )
        }
    }
}

@Composable
fun SearchSuggestionsList(
    suggestions: List<SearchSuggestion>,
    repository: BockMediaRepository,
    onSuggestionClick: (SearchSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Column(modifier) {
        suggestions.forEach { suggestion ->
            SearchSuggestionRow(
                suggestion = suggestion,
                repository = repository,
                onClick = { onSuggestionClick(suggestion) },
            )
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    suggestion: SearchSuggestion,
    repository: BockMediaRepository,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = suggestion.title,
        artPath = suggestion.path,
        playlistId = if (suggestion.kind == SearchSuggestionKind.Playlist) suggestion.id else null,
        artistName = if (suggestion.kind == SearchSuggestionKind.Artist) suggestion.title else null,
        albumName = if (suggestion.kind == SearchSuggestionKind.Album) suggestion.title else null,
        albumArtist = suggestion.artist,
        variantKey = suggestion.id ?: suggestion.path ?: suggestion.title,
    )
    ListItem(
        headlineContent = { Text(suggestion.title) },
        supportingContent = suggestion.subtitle?.let { sub -> { Text(sub) } },
        leadingContent = {
            BockArtwork(
                model = artUrl,
                title = suggestion.title,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(6.dp),
                fallbackFontSize = 14.sp,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun SearchPinsSection(
    pins: List<com.bockmedia.console.data.api.dto.SearchPin>,
    repository: BockMediaRepository,
    onPlay: (com.bockmedia.console.domain.model.PlayTarget) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pins.isEmpty()) return
    Column(modifier) {
        Text(
            "Aural fixations",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(pins, key = { "${it.kind}-${it.id ?: it.name ?: it.title}" }) { pin ->
                val title = pin.title ?: pin.name ?: "Shortcut"
                val artUrl = rememberArtworkUrl(
                    repository = repository,
                    title = title,
                    artPath = pin.path,
                    playlistId = pin.id,
                    artistName = if (pin.kind == "artist") pin.name else null,
                    albumName = if (pin.kind == "album") pin.name else null,
                    albumArtist = pin.artist,
                    variantKey = pin.id ?: pin.path ?: title,
                )
                Column(
                    Modifier
                        .width(112.dp)
                        .clickable {
                            when (pin.kind.lowercase()) {
                                "playlist" -> pin.id?.let(onOpenPlaylist)
                                "genre" -> (pin.name ?: title).let(onOpenGenre)
                                "artist" -> (pin.name ?: title).let(onOpenArtist)
                                "album" -> onOpenAlbum(pin.name ?: title, pin.artist)
                                "radio" -> onPlay(
                                    com.bockmedia.console.domain.model.PlayTarget.Radio(
                                        "$title Radio",
                                        com.bockmedia.console.domain.model.PlayTarget.RadioSeedKind.Artist,
                                        pin.name ?: title,
                                        pin.path,
                                    ),
                                )
                                else -> pin.path?.let {
                                    onPlay(com.bockmedia.console.domain.model.PlayTarget.Song(it, title))
                                }
                            }
                        },
                ) {
                    BockArtwork(
                        model = artUrl,
                        title = title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = TileShape,
                        fallbackFontSize = 22.sp,
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
