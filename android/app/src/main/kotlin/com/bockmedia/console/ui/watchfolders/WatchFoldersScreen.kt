package com.bockmedia.console.ui.watchfolders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.WatchFolder
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.LoadingBox

@Composable
fun WatchFoldersScreen(repository: BockMediaRepository) {
    var items by remember { mutableStateOf<List<WatchFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runCatching { items = repository.watchFolders() }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            LazyColumn {
                items(items) { wf ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(wf.label ?: wf.path ?: "Folder", style = MaterialTheme.typography.titleSmall)
                            Text("Status: ${wf.status ?: "—"}")
                            Text("${wf.songs} songs · ${wf.playlists} playlists")
                            wf.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}
