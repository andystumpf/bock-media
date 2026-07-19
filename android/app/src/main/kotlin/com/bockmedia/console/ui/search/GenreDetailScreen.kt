package com.bockmedia.console.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.ArtistItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeFeedRules
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun GenreDetailScreen(
    genreName: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
) {
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var trackCount by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }

    val genreArtUrl = rememberArtworkUrl(
        repository = repository,
        title = genreName,
        artPath = null,
        variantKey = "genre-$genreName",
        genreName = genreName,
    )

    LaunchedEffect(genreName) {
        loading = true
        runCatching {
            coroutineScope {
                val songsDef = async { repository.songs(page = 1, genre = genreName, limit = 200) }
                val genresDef = async { repository.genres(limit = 200) }
                val songs = songsDef.await().items
                val genreMeta = genresDef.await().items.let { items ->
                    HomeFeedRules.matchingLibraryGenreForLabel(genreName, items)
                }
                trackCount = genreMeta?.tracks ?: songs.size
                albums = songs
                    .filter { !it.album.isNullOrBlank() }
                    .groupBy { "${it.album}|${it.artist.orEmpty()}" }
                    .map { (_, tracks) ->
                        val first = tracks.first()
                        AlbumItem(
                            name = first.album.orEmpty(),
                            artist = first.artist,
                            tracks = tracks.size,
                            artPath = first.path,
                        )
                    }
                    .sortedByDescending { it.tracks }
                    .take(12)
                artists = songs
                    .filter { !it.artist.isNullOrBlank() }
                    .groupBy { it.artist!! }
                    .map { (name, tracks) -> ArtistItem(name = name, tracks = tracks.size) }
                    .sortedByDescending { it.tracks }
                    .take(12)
            }
        }
        loading = false
    }

    val radioTarget = PlayTarget.Radio(
        displayTitle = "$genreName Radio",
        seedKind = PlayTarget.RadioSeedKind.Genre,
        name = genreName,
    )

    Column(Modifier.fillMaxSize().testTag(BockTestTags.GENRE_DETAIL_BODY)) {
        if (loading) {
            LoadingBox(Modifier.weight(1f))
        } else {
            BockLazyColumn(Modifier.weight(1f)) {
                item {
                    GenreHeroBanner(
                        genreName = genreName,
                        trackCount = trackCount,
                        artUrl = genreArtUrl,
                        remoteOk = remoteOk,
                        onPlayRadio = { onPlay(radioTarget) },
                    )
                }
                if (albums.isNotEmpty()) {
                    item { BockDetailSectionHeader("Top albums") }
                    albums.forEach { album ->
                        val albumName = album.name
                        item(key = "al-$albumName-${album.artist}") {
                            LibraryArtListItem(
                                repository = repository,
                                title = albumName,
                                subtitle = album.artist.orEmpty(),
                                artPath = album.artPath,
                                albumName = albumName,
                                artistName = album.artist,
                                modifier = Modifier
                                    .clickable { onOpenAlbum(albumName, album.artist) }
                                    .padding(horizontal = 4.dp),
                                trailing = {
                                    PlayButton(onClick = {
                                        onPlay(PlayTarget.Album(albumName, album.artist))
                                    }, enabled = remoteOk)
                                },
                            )
                        }
                    }
                }
                if (artists.isNotEmpty()) {
                    item { BockDetailSectionHeader("Top artists") }
                    artists.forEach { artist ->
                        item(key = "ar-${artist.name}") {
                            LibraryArtListItem(
                                repository = repository,
                                title = artist.name,
                                subtitle = "${artist.tracks} tracks",
                                artistName = artist.name,
                                artShape = CircleShape,
                                modifier = Modifier
                                    .clickable { onOpenArtist(artist.name) }
                                    .padding(horizontal = 4.dp),
                                trailing = {
                                    PlayButton(onClick = {
                                        onPlay(PlayTarget.Artist(artist.name))
                                    }, enabled = remoteOk)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreHeroBanner(
    genreName: String,
    trackCount: Int,
    artUrl: String?,
    remoteOk: Boolean,
    onPlayRadio: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        BockArtwork(
            model = artUrl,
            title = genreName,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            fallbackFontSize = 28.sp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.15f),
                            Color.Black.copy(alpha = 0.75f),
                        ),
                    ),
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                genreName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (trackCount > 0) {
                Text(
                    "$trackCount tracks in library",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = onPlayRadio,
                enabled = remoteOk,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BockGreen,
                    contentColor = Color.Black,
                ),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play radio", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
