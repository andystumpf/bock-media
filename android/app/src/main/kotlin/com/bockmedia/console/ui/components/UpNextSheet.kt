package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.UpcomingTrack
import com.bockmedia.console.data.repository.BockMediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(
    tracks: List<UpcomingTrack>,
    repository: BockMediaRepository,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            tracks.forEachIndexed { index, track ->
                LibraryArtListItem(
                    repository = repository,
                    title = track.title ?: "Track ${index + 1}",
                    subtitle = track.artist ?: "",
                    artPath = track.path,
                )
            }
        }
    }
}
