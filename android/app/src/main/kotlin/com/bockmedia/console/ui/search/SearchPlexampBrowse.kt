package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.SearchRecentSelection
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.rememberArtworkUrl

@Composable
fun SearchPlexampBrowse(
    repository: BockMediaRepository,
    recentSelections: List<SearchRecentSelection>,
    customPins: List<com.bockmedia.console.data.api.dto.SearchPin>,
    onAuralFixation: (SearchRankingKind) -> Unit,
    onSonicAdventure: () -> Unit,
    onSonicSage: () -> Unit,
    onEditShortcuts: () -> Unit,
    onPinClick: (com.bockmedia.console.data.api.dto.SearchPin) -> Unit,
    onRecentClick: (SearchRecentSelection) -> Unit,
    onRecentRemove: (SearchRecentSelection) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlexampBrowseSectionHeader("Aural fixations", modifier = Modifier.weight(1f))
            TextButton(onClick = onEditShortcuts) {
                Text("Edit", style = MaterialTheme.typography.labelMedium)
            }
        }
        SearchBrowseLinkRow("Top Artists", Icons.Default.Mic, onClick = { onAuralFixation(SearchRankingKind.Artists) })
        SearchBrowseLinkRow("Top Albums", Icons.Default.Album, onClick = { onAuralFixation(SearchRankingKind.Albums) })
        SearchBrowseLinkRow("Top Tracks", Icons.Default.MusicNote, onClick = { onAuralFixation(SearchRankingKind.Tracks) })
        SearchBrowseLinkRow("Best Of…", Icons.Default.Star, onClick = { onAuralFixation(SearchRankingKind.BestOf) })

        customPins.forEach { pin ->
            val title = pin.title ?: pin.name ?: "Shortcut"
            SearchBrowseLinkRow(title, Icons.Default.Bookmark, onClick = { onPinClick(pin) })
        }

        Spacer(Modifier.height(16.dp))
        PlexampBrowseSectionHeader("Sonic explorations")
        SearchBrowseLinkRow("Sonic Adventure", Icons.Default.Explore, onClick = onSonicAdventure)
        SearchBrowseLinkRow("Sonic Sage", Icons.Default.AutoAwesome, onClick = onSonicSage)

        if (recentSelections.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlexampBrowseSectionHeader("Recent searches", modifier = Modifier.weight(1f))
                IconButton(onClick = onClearRecent, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "Clear recent searches")
                }
            }
            recentSelections.forEach { item ->
                SearchRecentSelectionRow(
                    repository = repository,
                    selection = item,
                    onClick = { onRecentClick(item) },
                    onRemove = { onRecentRemove(item) },
                )
            }
        }
    }
}

@Composable
private fun PlexampBrowseSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchBrowseLinkRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SearchRecentSelectionRow(
    repository: BockMediaRepository,
    selection: SearchRecentSelection,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val isArtist = selection.kind == "artist"
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = selection.title,
        artPath = selection.path,
        playlistId = selection.id,
        artistName = if (isArtist) selection.title else null,
        albumName = if (selection.kind == "album") selection.title else null,
        albumArtist = selection.artist,
        variantKey = selection.key,
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
            title = selection.title,
            modifier = Modifier.size(48.dp),
            shape = if (isArtist) CircleShape else RoundedCornerShape(4.dp),
            fallbackFontSize = 16.sp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(selection.title, style = MaterialTheme.typography.bodyLarge)
            selection.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove")
        }
    }
}
