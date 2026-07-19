package com.bockmedia.console.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.ArtistDetailAbout
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtistDetailRules
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.rememberArtworkUrl
import com.bockmedia.console.ui.theme.BockGreen

@Composable
fun CollapsibleSectionHeader(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: (() -> Unit)? = null,
    playContentDescription: String = "Play all",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onToggle),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
            }
        }
        if (onPlay != null) {
            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = playContentDescription,
                    tint = BockGreen,
                )
            }
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
fun ArtistLibraryStatsStrip(
    trackCount: Int,
    albumCount: Int,
    totalPlays: Int,
) {
    Row(
        Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (trackCount > 0) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = BockGreen, modifier = Modifier.size(16.dp))
            Text("Verified library", style = MaterialTheme.typography.labelMedium, color = BockGreen)
        }
    }
    val parts = buildList {
        if (totalPlays > 0) add("${ArtistDetailRules.formatLibraryCount(totalPlays)} plays")
        if (trackCount > 0) add("$trackCount tracks")
        if (albumCount > 0) add("$albumCount albums")
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.65f),
        )
    }
}

@Composable
fun ArtistLatestAlbumCard(
    album: AlbumItem,
    artistName: String,
    repository: BockMediaRepository,
    onClick: () -> Unit,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = album.name,
        artPath = album.artPath,
        albumName = album.name,
        albumArtist = album.artist,
        variantKey = "latest-${album.name}",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BockArtwork(
            model = artUrl,
            title = album.name,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(6.dp)),
            shape = RoundedCornerShape(6.dp),
            fallbackFontSize = 20.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Latest in library", style = MaterialTheme.typography.labelMedium, color = BockGreen)
            Text(album.name, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(album.year?.toString(), artistName).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
        }
    }
}

@Composable
fun ArtistAboutSection(about: ArtistDetailAbout?, artistName: String, trackCount: Int) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("About $artistName", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        about?.firstAdded?.takeIf { it.isNotBlank() }?.let {
            AboutFactRow(label = "First added to library", value = it)
        }
        about?.topDecade?.takeIf { it > 0 }?.let {
            AboutFactRow(label = "Most played decade", value = "${it}s")
        }
        if (trackCount > 0) {
            AboutFactRow(label = "Tracks in library", value = trackCount.toString())
        }
        if (!about?.topGenres.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Top genres", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
            Text(
                about!!.topGenres.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AboutFactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}

@Composable
fun TrackLikeButton(
    liked: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
        Icon(
            if (liked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = if (liked) "Unlike" else "Like",
            tint = if (liked) BockGreen else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
fun ArtistSectionTabs(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        listOf("music" to "Music", "about" to "About").forEach { (id, label) ->
            Column(Modifier.clickable { onSelect(id) }) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected == id) Color.White else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (selected == id) {
                    Box(Modifier.width(48.dp).height(2.dp).background(BockGreen))
                }
            }
        }
    }
}
