package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.ArtistItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.launch

@Composable
fun GenreDetailScreen(
    genreName: String,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(genreName) {
        loading = true
        runCatching {
            val songs = repository.songs(page = 1, genre = genreName, limit = 200).items
            albums = songs
                .filter { !it.album.isNullOrBlank() }
                .groupBy { "${it.album}|${it.artist.orEmpty()}" }
                .map { (_, tracks) ->
                    val first = tracks.first()
                    AlbumItem(
                        name = first.album.orEmpty(),
                        artist = first.artist,
                        tracks = tracks.size,
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
        loading = false
    }

    val radioTarget = PlayTarget.Radio(
        displayTitle = "$genreName Radio",
        seedKind = PlayTarget.RadioSeedKind.Genre,
        name = genreName,
    )

    Column(Modifier.fillMaxSize()) {
        if (loading) {
            LoadingBox(Modifier.weight(1f))
        } else {
            BockLazyColumn(Modifier.weight(1f)) {
                item {
                    Surface(
                        onClick = { onPlay(radioTarget) },
                        shape = RoundedCornerShape(12.dp),
                        color = BockGreen,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Play $genreName radio",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (albums.isNotEmpty()) {
                    item {
                        Text(
                            "Top albums",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    albums.forEach { album ->
                        val albumName = album.name
                        item(key = "al-$albumName-${album.artist}") {
                            LibraryArtListItem(
                                repository = repository,
                                title = albumName,
                                subtitle = album.artist.orEmpty(),
                                artPath = null,
                                modifier = Modifier
                                    .clickable { onOpenAlbum(albumName, album.artist) }
                                    .padding(horizontal = 8.dp),
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
                    item {
                        Text(
                            "Top artists",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    artists.forEach { artist ->
                        item(key = "ar-${artist.name}") {
                            LibraryArtListItem(
                                repository = repository,
                                title = artist.name,
                                subtitle = "${artist.tracks} tracks",
                                artPath = null,
                                modifier = Modifier
                                    .clickable { onOpenArtist(artist.name) }
                                    .padding(horizontal = 8.dp),
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
