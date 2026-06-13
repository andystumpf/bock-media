package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SummaryResponse
import com.bockmedia.console.data.repository.BockMediaRepository

@Composable
fun LibraryStatsCard(
    repository: BockMediaRepository,
    modifier: Modifier = Modifier,
) {
    var summary by remember { mutableStateOf<SummaryResponse?>(null) }
    LaunchedEffect(repository) {
        runCatching { summary = repository.summary() }
    }
    val data = summary ?: return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your library at a glance", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatCell("Songs", data.songs)
                StatCell("Artists", data.artists)
                StatCell("Albums", data.albums)
                StatCell("Playlists", data.playlists)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, count: Int) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
