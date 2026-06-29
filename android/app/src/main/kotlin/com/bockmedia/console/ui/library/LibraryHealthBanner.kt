package com.bockmedia.console.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.LibraryHealthResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LibraryHealthBanner(
    health: LibraryHealthResponse,
    repository: BockMediaRepository,
    onMerged: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val summary = health.summary
    if (summary.needsAttention <= 0 && health.duplicateArtists.isEmpty()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Library needs attention", fontWeight = FontWeight.SemiBold)
            }
            if (summary.needsAttention > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${summary.needsAttention} tracks missing genre or album artist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (health.duplicateArtists.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${health.duplicateArtists.size} artist name duplicates (e.g. spelling variants)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show fix queues")
            }
            if (expanded) {
                if (health.attentionFolders.isNotEmpty()) {
                    Text("Tag these folders in Picard first:", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    health.attentionFolders.forEach { folder ->
                        Text(
                            "${folder.trackCount} tracks · ${File(folder.path).name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                health.duplicateArtists.take(3).forEach { group ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        group.variants.joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    TextButton(
                        enabled = !merging,
                        onClick = {
                            merging = true
                            scope.launch {
                                val others = group.variants.filter { it != group.canonical }
                                runCatching {
                                    repository.mergeArtists(from = others, to = group.canonical)
                                }.onSuccess {
                                    message = "Merged into \"${group.canonical}\""
                                    onMerged()
                                }.onFailure {
                                    message = it.message ?: "Merge failed"
                                }
                                merging = false
                            }
                        },
                    ) {
                        Text("Merge → ${group.canonical}")
                    }
                }
            }
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
