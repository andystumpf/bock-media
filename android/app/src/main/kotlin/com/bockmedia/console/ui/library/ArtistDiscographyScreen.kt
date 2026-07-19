package com.bockmedia.console.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtistAlbumSort
import com.bockmedia.console.domain.model.ArtistDetailRules
import com.bockmedia.console.domain.model.ArtistDiscographyFilter
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.rememberArtworkUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDiscographyScreen(
    artistName: String,
    albums: List<AlbumItem>,
    appearsOnNames: Set<String>,
    repository: BockMediaRepository,
    onBack: () -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
) {
    var filter by remember { mutableStateOf(ArtistDiscographyFilter.Studio) }
    var sort by remember { mutableStateOf(ArtistAlbumSort.Newest) }
    val filtered = remember(albums, filter, appearsOnNames, sort) {
        ArtistDetailRules.sortAlbums(
            ArtistDetailRules.filterDiscography(albums, filter, appearsOnNames),
            sort,
        )
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("$artistName — Discography", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ArtistDiscographyFilter.entries.size) { idx ->
                    val f = ArtistDiscographyFilter.entries[idx]
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f.label) },
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ArtistAlbumSort.entries.size) { idx ->
                    val s = ArtistAlbumSort.entries[idx]
                    FilterChip(
                        selected = sort == s,
                        onClick = { sort = s },
                        label = {
                            Text(
                                when (s) {
                                    ArtistAlbumSort.Newest -> "Newest"
                                    ArtistAlbumSort.Oldest -> "Oldest"
                                    ArtistAlbumSort.NameAz -> "A–Z"
                                },
                            )
                        },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "${it.name}|${it.artist}" }) { album ->
                    DiscographyGridCard(
                        album = album,
                        repository = repository,
                        onClick = { onOpenAlbum(album.name, album.artist ?: artistName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscographyGridCard(
    album: AlbumItem,
    repository: BockMediaRepository,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = album.name,
        artPath = album.artPath,
        albumName = album.name,
        albumArtist = album.artist,
        variantKey = "${album.name}|${album.artist}",
    )
    Column(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        BockArtwork(
            model = artUrl,
            title = album.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp)),
            shape = RoundedCornerShape(4.dp),
            fallbackFontSize = 18.sp,
        )
        Text(
            album.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val meta = listOfNotNull(
            album.year?.takeIf { it > 0 }?.toString(),
            "${album.tracks} tracks".takeIf { album.tracks > 0 },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}
