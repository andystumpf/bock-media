package com.bockmedia.console.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.UpcomingTrack
import com.bockmedia.console.data.repository.BockMediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(
    tracks: List<UpcomingTrack>,
    repository: BockMediaRepository,
    isLocalPlayback: Boolean = false,
    onPlayAtIndex: (Int) -> Unit = {},
    onPlayNowAlexa: (Int) -> Unit = {},
    onAlexaUnsupported: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            item {
                Text(
                    "Up next",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            itemsIndexed(tracks, key = { index, track -> "${index}-${track.path ?: track.title}" }) { index, track ->
                LibraryArtListItem(
                    repository = repository,
                    title = track.title ?: "Track ${index + 1}",
                    subtitle = track.artist ?: "",
                    artPath = track.path,
                    modifier = Modifier.clickable {
                        if (isLocalPlayback) onPlayAtIndex(index)
                        else onPlayNowAlexa(index)
                    },
                    trailing = {
                        IconButton(onClick = {
                            if (isLocalPlayback) onPlayAtIndex(index)
                            else onPlayNowAlexa(index)
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play now")
                        }
                    },
                )
            }
        }
    }
}
