package com.bockmedia.console.ui.watchfolders

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.WatchFolder
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun WatchFoldersScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WatchFolder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        error = null
        runCatching { items = repository.watchFolders() }
            .onFailure { error = it.message ?: "Failed to load watch folders" }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when {
            loading -> LoadingBox(Modifier.weight(1f))
            error != null -> ErrorText(error!!, onRetry = { scope.launch { load() } })
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text("No watch folders configured.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                BockLazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.guid ?: it.path ?: it.hashCode().toString() }) { wf ->
                        WatchFolderCard(wf)
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchFolderCard(wf: WatchFolder) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    wf.label?.takeIf { it.isNotBlank() } ?: wf.path ?: "Folder",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                wf.status?.let { status ->
                    AssistChip(
                        onClick = {},
                        label = { Text(status) },
                        enabled = false,
                    )
                }
            }
            wf.path?.takeIf { wf.label?.isNotBlank() == true }?.let { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                if (wf.identifiedFiles > 0) add("${wf.identifiedFiles} tracks")
                else add("0 identified")
                if (wf.playlists > 0) add("${wf.playlists} playlists")
                wf.type?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (wf.errors > 0) {
                Text(
                    "${wf.errors} errors",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
