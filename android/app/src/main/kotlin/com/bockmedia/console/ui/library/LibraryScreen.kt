package com.bockmedia.console.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SummaryResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    repository: BockMediaRepository,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenSongs: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<SummaryResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        if (summary == null) loading = true
        runCatching { summary = repository.summary() }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }

    if (loading && summary == null) {
        LoadingBox()
        return
    }

    BockPullRefresh(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; scope.launch { load() } },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            summary?.let {
                Text(
                    "${it.songs} songs · ${it.artists} artists · ${it.albums} albums",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LibraryEntry(
                icon = { Icon(Icons.Default.Mic, null) },
                title = "Artists",
                subtitle = summary?.let { "${it.artists} artists" } ?: "Browse by artist",
                onClick = onOpenArtists,
            )
            LibraryEntry(
                icon = { Icon(Icons.Default.Album, null) },
                title = "Albums",
                subtitle = summary?.let { "${it.albums} albums" } ?: "Browse by album",
                onClick = onOpenAlbums,
            )
            LibraryEntry(
                icon = { Icon(Icons.Default.MusicNote, null) },
                title = "Songs",
                subtitle = summary?.let { "${it.songs} songs" } ?: "Browse all songs",
                onClick = onOpenSongs,
            )
        }
    }
}

@Composable
private fun LibraryEntry(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
