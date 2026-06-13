package com.bockmedia.console.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.launch

private val SheetBg = Color(0xFF282828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    repository: BockMediaRepository,
    trackPath: String,
    trackTitle: String,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { playlists = repository.playlists(limit = 300).items }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SheetBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Add to playlist",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                trackTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (showNew) {
                OutlinedTextField(
                    newName,
                    { newName = it },
                    label = { Text("New playlist name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { showNew = false; newName = "" }) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                saving = true
                                runCatching {
                                    repository.createPlaylist(newName.trim(), listOf(trackPath))
                                    onAdded("Added to \"${newName.trim()}\"")
                                    onDismiss()
                                }
                                saving = false
                            }
                        },
                        enabled = newName.isNotBlank() && !saving,
                    ) { Text("Create & add") }
                }
            } else {
                ListItem(
                    headlineContent = { Text("Create new playlist", color = Color.White) },
                    leadingContent = { Icon(Icons.Default.Add, null, tint = BockGreen) },
                    modifier = Modifier.clickable { showNew = true },
                )
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    BockProgressIndicator()
                }
            } else {
                playlists.forEach { pl ->
                    ListItem(
                        headlineContent = { Text(pl.name, color = Color.White) },
                        supportingContent = {
                            Text(
                                "${pl.tracks} tracks",
                                color = Color.White.copy(alpha = 0.55f),
                            )
                        },
                        modifier = Modifier.clickable(enabled = !saving) {
                            scope.launch {
                                saving = true
                                runCatching {
                                    repository.addPlaylistTrack(pl.id, trackPath)
                                    onAdded("Added to \"${pl.name}\"")
                                    onDismiss()
                                }
                                saving = false
                            }
                        },
                    )
                }
            }
        }
    }
}
