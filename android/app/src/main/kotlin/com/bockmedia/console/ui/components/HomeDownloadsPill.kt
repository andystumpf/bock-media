package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OfflineCollectionStatus
import com.bockmedia.console.ui.downloads.rememberVisibleDownloadStatuses

private val PillShape = RoundedCornerShape(50)
private val SpotifySheetBg = Color(0xFF282828)

@Composable
fun HomeDownloadsPillRow(
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statuses = rememberVisibleDownloadStatuses()
    val active = remember(statuses) {
        statuses.values.filter { it.state == DownloadState.Downloading || it.state == DownloadState.Failed }
    }
    if (active.isEmpty()) return

    var showSheet by remember { mutableStateOf(false) }
    val downloading = active.filter { it.state == DownloadState.Downloading }
    val aggregateProgress = remember(downloading) {
        if (downloading.isEmpty()) 0f
        else downloading.sumOf { it.progress.toDouble() }.toFloat() / downloading.size
    }
    val pillLabel = when {
        downloading.size == 1 -> {
            val pct = (downloading.first().progress * 100).toInt()
            "Downloading · $pct%"
        }
        downloading.isNotEmpty() -> "Downloading · ${downloading.size}"
        active.any { it.state == DownloadState.Failed } -> "Download failed"
        else -> "Downloads"
    }

    Surface(
        onClick = { showSheet = true },
        shape = PillShape,
        color = BockGreen.copy(alpha = 0.18f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, tint = BockGreen, modifier = Modifier.size(16.dp))
                Text(
                    pillLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BockGreen,
                )
            }
            if (downloading.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { aggregateProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(96.dp)
                        .height(3.dp),
                    color = BockGreen,
                    trackColor = BockGreen.copy(alpha = 0.25f),
                )
            }
        }
    }

    if (showSheet) {
        HomeDownloadsProgressSheet(
            items = active.sortedByDescending { it.manifest.downloadedAtMs },
            onDismiss = { showSheet = false },
            onOpenDownloads = {
                showSheet = false
                onOpenDownloads()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeDownloadsProgressSheet(
    items: List<OfflineCollectionStatus>,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SpotifySheetBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Downloads",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.heightIn(max = 360.dp),
            ) {
                items(items, key = { it.manifest.id }) { status ->
                    DownloadProgressRow(status)
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
                Text("Manage downloads")
            }
        }
    }
}

@Composable
private fun DownloadProgressRow(status: OfflineCollectionStatus) {
    val manifest = status.manifest
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                manifest.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            when (status.state) {
                DownloadState.Downloading -> {
                    Text(
                        "${(status.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = BockGreen,
                    )
                }
                DownloadState.Failed -> {
                    Text(
                        "Failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> Unit
            }
        }
        Spacer(Modifier.height(6.dp))
        when (status.state) {
            DownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { status.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = BockGreen,
                    trackColor = Color.White.copy(alpha = 0.15f),
                )
            }
            DownloadState.Failed -> {
                Text(
                    status.error ?: "Download failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> Unit
        }
    }
}
