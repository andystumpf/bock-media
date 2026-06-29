package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.rememberArtworkUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SonicAdventureStop(
    val title: String,
    val subtitle: String,
    val album: AlbumItem?,
    val genre: GenreItem?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSonicAdventureScreen(
    repository: BockMediaRepository,
    onBack: () -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onOpenGenre: (String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var stops by remember { mutableStateOf<List<SonicAdventureStop>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching {
            coroutineScope {
                val albumsDef = async { repository.albums(page = 1, search = "", limit = 80).items }
                val genresDef = async { repository.genres(limit = 16).items }
                val newDef = async { repository.recentAlbums(limit = 12).items }
                val albums = albumsDef.await()
                val genres = genresDef.await()
                val newAlbums = newDef.await()
                val picks = buildList {
                    albums.filter { it.unplayed }.shuffled().take(3).forEach { album ->
                        add(SonicAdventureStop(album.name, album.artist ?: "Unplayed album", album, null))
                    }
                    newAlbums.shuffled().take(2).forEach { album ->
                        add(SonicAdventureStop(album.name, "New release · ${album.artist ?: ""}", album, null))
                    }
                    genres.shuffled().take(2).forEach { genre ->
                        add(SonicAdventureStop(genre.name, "Explore genre", null, genre))
                    }
                }.distinctBy { it.title }.take(6)
                stops = picks
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sonic Adventure") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        Text(
            "Pick a destination — albums and genres you may not have explored lately.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (loading) {
            LoadingBox(Modifier.weight(1f))
        } else if (stops.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No adventure stops found yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(stops.size) { index ->
                    val stop = stops[index]
                    SonicAdventureRow(
                        index = index + 1,
                        stop = stop,
                        repository = repository,
                        onClick = {
                            when {
                                stop.album != null -> onOpenAlbum(stop.album.name, stop.album.artist)
                                stop.genre != null -> onOpenGenre(stop.genre.name)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SonicAdventureRow(
    index: Int,
    stop: SonicAdventureStop,
    repository: BockMediaRepository,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = stop.title,
        artPath = stop.album?.artPath ?: stop.genre?.artPath,
        albumName = stop.album?.name,
        albumArtist = stop.album?.artist,
        variantKey = stop.title,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$index",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        BockArtwork(
            model = artUrl,
            title = stop.title,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(6.dp),
            fallbackFontSize = 18.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stop.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(stop.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
