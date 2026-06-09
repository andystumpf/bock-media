package com.bockmedia.console.ui.settings

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.HealthStatusCard
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.ui.downloads.DownloadsManagementSection
import com.bockmedia.console.ui.watchfolders.WatchFoldersSection
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState? = null,
    onOpenNowPlaying: () -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember { BockMediaApp.get(context) }
    var wifiOnlyDownloads by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        wifiOnlyDownloads = app.preferences.isDownloadWifiOnlySync()
        loading = false
    }

    Column(Modifier.fillMaxSize().bockVerticalScroll().padding(16.dp)) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (loading) LoadingBox() else {
            SettingsSectionHeader(
                title = "Downloads",
                icon = Icons.Default.Download,
                subtitle = "Offline music saved on this phone",
            )
            DownloadsManagementSection(
                embedded = true,
                onOpenPlaylist = onOpenPlaylist,
                snackbarHostState = snackbarHostState,
                onOpenNowPlaying = onOpenNowPlaying,
            )
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Download over Wi‑Fi only", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Cellular downloads pause until you're on Wi‑Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = wifiOnlyDownloads,
                        onCheckedChange = { checked ->
                            wifiOnlyDownloads = checked
                            scope.launch { app.preferences.setDownloadWifiOnly(checked) }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(
                title = "Watch folders",
                icon = Icons.Default.Folder,
                subtitle = "Library folders scanned on the server",
            )
            WatchFoldersSection(repository = repository)

            Spacer(Modifier.height(16.dp))
            HealthStatusCard(
                repository = repository,
                onMessage = { msg ->
                    message = msg
                    scope.launch {
                        snackbarHostState?.showSnackbar(msg)
                    }
                },
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
