package com.bockmedia.console.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.settings.AlexaRemoteCard
import com.bockmedia.console.ui.testing.BockTestTags
import com.bockmedia.console.ui.components.HealthStatusCard
import com.bockmedia.console.ui.components.LibraryStatsCard
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.ui.downloads.DownloadsManagementSection
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.ui.watchfolders.WatchFoldersSection
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun SettingsScreen(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState? = null,
    onOpenNowPlaying: () -> Unit = {},
    onOpenPlaylist: (String) -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember { BockMediaApp.get(context) }
    var wifiOnlyDownloads by remember { mutableStateOf(false) }
    var crossfadeSeconds by remember { mutableFloatStateOf(0f) }
    var continueAfterQueue by remember { mutableStateOf("off") }
    val continueOptions = listOf("off" to "Stop", "similar" to "Similar songs", "artist_radio" to "Artist radio")
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        wifiOnlyDownloads = app.preferences.isDownloadWifiOnlySync()
        crossfadeSeconds = app.preferences.getCrossfadeSecondsSync().toFloat()
        continueAfterQueue = app.preferences.getContinueAfterQueueSync()
        loading = false
    }

    val activeMemberId by ActiveProfileStore.activeMemberIdState.collectAsState()
    val profileRevision by ClientPrefsSync.profileChangeRevision.collectAsState()
    LaunchedEffect(activeMemberId, profileRevision) {
        wifiOnlyDownloads = app.preferences.isDownloadWifiOnlySync()
        crossfadeSeconds = app.preferences.getCrossfadeSecondsSync().toFloat()
        continueAfterQueue = app.preferences.getContinueAfterQueueSync()
    }

    Column(
        Modifier
            .fillMaxSize()
            .testTag(BockTestTags.SETTINGS_BODY)
            .bockVerticalScroll()
            .padding(16.dp),
    ) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (loading) LoadingBox() else {
            LibraryStatsCard(repository = repository, modifier = Modifier.padding(bottom = 16.dp))
            AlexaRemoteCard(
                repository = repository,
                onMessage = { msg ->
                    message = msg
                    scope.launch { snackbarHostState?.showSnackbar(msg) }
                },
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenPlaylists, modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Manage playlists")
            }
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
                            scope.launch {
                                app.preferences.setDownloadWifiOnly(checked)
                                ClientPrefsSync.schedulePush(context)
                            }
                        },
                        modifier = Modifier.testTag(BockTestTags.SETTINGS_WIFI_ONLY),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsSectionHeader(
                title = "This Phone playback",
                icon = Icons.Default.Settings,
                subtitle = "Local audio on this device only",
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Crossfade", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (crossfadeSeconds <= 0f) {
                            "Off — hard cut between songs"
                        } else {
                            "${crossfadeSeconds.toInt()} s overlap before each track ends"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = crossfadeSeconds,
                        onValueChange = { crossfadeSeconds = it },
                        onValueChangeFinished = {
                            scope.launch {
                                app.preferences.setCrossfadeSeconds(crossfadeSeconds.toInt())
                                ClientPrefsSync.schedulePush(context)
                            }
                        },
                        valueRange = 0f..20f,
                        steps = 19,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("When queue ends", fontWeight = FontWeight.SemiBold)
                    continueOptions.forEach { (value, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag(BockTestTags.settingsContinue(value))
                                .clickable {
                                continueAfterQueue = value
                                scope.launch {
                                    app.preferences.setContinueAfterQueue(value)
                                    ClientPrefsSync.schedulePush(context)
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = continueAfterQueue == value,
                                onClick = {
                                    continueAfterQueue = value
                                    scope.launch {
                                        app.preferences.setContinueAfterQueue(value)
                                        ClientPrefsSync.schedulePush(context)
                                    }
                                },
                            )
                            Text(label)
                        }
                    }
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
            ServerConfigSection(
                repository = repository,
                onMessage = { msg ->
                    message = msg
                    scope.launch { snackbarHostState?.showSnackbar(msg) }
                },
            )

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
private fun ServerConfigSection(
    repository: BockMediaRepository,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var defaultPlaylist by remember { mutableStateOf("") }
    var publicUrl by remember { mutableStateOf("") }
    var flacSupport by remember { mutableStateOf("") }
    var transcodeBitrate by remember { mutableStateOf("") }

    LaunchedEffect(expanded, confirmed) {
        if (!expanded || !confirmed) return@LaunchedEffect
        loading = true
        runCatching {
            val settings = repository.loadSettingsJson()
            val config = repository.loadConfigJson()
            defaultPlaylist = settings["defaultPlaylist"]?.jsonPrimitive?.content.orEmpty()
            publicUrl = config["publicUrl"]?.jsonPrimitive?.content.orEmpty()
            flacSupport = settings["flacSupport"]?.jsonPrimitive?.content.orEmpty()
            transcodeBitrate = settings["transcodeBitrate"]?.jsonPrimitive?.content.orEmpty()
        }.onFailure { onMessage(it.message ?: "Failed to load server config") }
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Server config", fontWeight = FontWeight.Bold)
                    Text(
                        "Power-user server preferences",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = expanded, onCheckedChange = { expanded = it; if (!it) confirmed = false })
            }
            if (expanded) {
                if (!confirmed) {
                    Text(
                        "Changes affect the Bock Media server. Continue?",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { confirmed = true }) { Text("Load server settings") }
                } else if (loading) {
                    LoadingBox(Modifier.height(120.dp))
                } else {
                    OutlinedTextField(
                        defaultPlaylist,
                        { defaultPlaylist = it },
                        label = { Text("Default playlist name") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    OutlinedTextField(
                        publicUrl,
                        { publicUrl = it },
                        label = { Text("Public URL") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "Read-only: FLAC support = $flacSupport · Transcode bitrate = $transcodeBitrate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    repository.saveSettings(buildJsonObject {
                                        put("defaultPlaylist", JsonPrimitive(defaultPlaylist))
                                    })
                                    repository.saveConfig(buildJsonObject {
                                        put("publicUrl", JsonPrimitive(publicUrl))
                                    })
                                    onMessage("Server settings saved")
                                }.onFailure { onMessage(it.message ?: "Save failed") }
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Save") }
                }
            }
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
