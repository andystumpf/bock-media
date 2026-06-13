package com.bockmedia.console.ui.search

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.PlayButton
import com.bockmedia.console.ui.components.SearchField
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(repository: BockMediaRepository, remoteOk: Boolean, onPlay: (PlayTarget) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        delay(280)
        if (query.length < 2) {
            results = null
            error = null
            return@LaunchedEffect
        }
        error = null
        runCatching { results = repository.search(query) }
            .onFailure {
                error = it.message ?: "Search failed"
                results = null
            }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(query, { query = it }, "Search library…")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(8.dp))
        BockLazyColumn {
            results?.playlists?.let { list ->
                item { Text("Playlists", style = MaterialTheme.typography.titleSmall) }
                items(list) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.name ?: "") },
                        trailingContent = {
                            if (remoteOk) PlayButton(onClick = {
                                onPlay(PlayTarget.Playlist(hit.id ?: "", hit.name ?: ""))
                            })
                        },
                    )
                }
            }
            results?.artists?.let { list ->
                item { Text("Artists", style = MaterialTheme.typography.titleSmall) }
                items(list) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.name ?: "") },
                        trailingContent = {
                            if (remoteOk) PlayButton(onClick = { onPlay(PlayTarget.Artist(hit.name ?: "")) })
                        },
                    )
                }
            }
            results?.albums?.let { list ->
                item { Text("Albums", style = MaterialTheme.typography.titleSmall) }
                items(list) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.name ?: "") },
                        supportingContent = { Text(hit.artist ?: "") },
                        trailingContent = {
                            if (remoteOk) PlayButton(onClick = { onPlay(PlayTarget.Album(hit.name ?: "", hit.artist)) })
                        },
                    )
                }
            }
            results?.songs?.let { list ->
                item { Text("Songs", style = MaterialTheme.typography.titleSmall) }
                items(list) { hit ->
                    ListItem(
                        headlineContent = { Text(hit.title ?: hit.name ?: "") },
                        supportingContent = { Text(hit.artist ?: "") },
                        trailingContent = {
                            if (remoteOk && !hit.path.isNullOrBlank()) {
                                PlayButton(onClick = {
                                    onPlay(PlayTarget.Song(hit.path!!, hit.title ?: hit.name ?: ""))
                                })
                            }
                        },
                    )
                }
            }
        }
    }
}
