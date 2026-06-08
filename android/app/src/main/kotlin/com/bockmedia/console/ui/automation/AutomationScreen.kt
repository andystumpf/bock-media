package com.bockmedia.console.ui.automation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AutomationItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.*
import com.bockmedia.console.ui.util.formatTime12
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AutomationItem>>(emptyList()) }
    var remoteOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var editId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var playlistSearch by remember { mutableStateOf("") }
    var playlistPick by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    var deviceValue by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("") }
    var deviceOptions by remember { mutableStateOf<List<DeviceOption>>(emptyList()) }
    var time by remember { mutableStateOf("08:00") }
    var volume by remember { mutableStateOf("") }
    var shuffle by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var playlistHits by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    suspend fun load() {
        loading = true
        runCatching {
            items = repository.automations().items
            val st = repository.alexaRemoteStatus()
            remoteOk = st.configured && st.authenticated == true
            if (remoteOk) {
                val devs = repository.alexaRemoteDevices().devices
                val groups = repository.deviceGroups().items
                deviceOptions = buildDeviceOptions(groups, devs)
            }
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(playlistSearch, playlistPick) {
        delay(250)
        if (playlistPick.first != null || playlistSearch.length < 1) {
            playlistHits = emptyList()
            return@LaunchedEffect
        }
        runCatching {
            playlistHits = repository.playlists(playlistSearch, limit = 25).items.map { it.id to it.name }
        }
    }

    fun resetForm() {
        editId = null
        label = ""
        playlistSearch = ""
        playlistPick = null to null
        playlistHits = emptyList()
        deviceValue = ""
        deviceName = ""
        time = "08:00"
        volume = ""
        shuffle = false
        enabled = true
    }

    BockLazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!remoteOk) {
            item {
                Text("Configure Alexa remote in Settings first.", color = MaterialTheme.colorScheme.error)
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BockTextField(label, { label = it }, "Label")
                    SearchField(
                        playlistSearch,
                        { query ->
                            playlistSearch = query
                            if (playlistPick.second != null && query != playlistPick.second) {
                                playlistPick = null to null
                            }
                        },
                        "Search playlist",
                    )
                    if (playlistPick.first == null && playlistHits.isNotEmpty()) {
                        playlistHits.take(8).forEach { (id, name) ->
                            TextButton(onClick = {
                                playlistPick = id to name
                                playlistSearch = name
                                playlistHits = emptyList()
                            }) { Text(name) }
                        }
                    }
                    playlistPick.second?.let { selected ->
                        Text(
                            "Selected: $selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DeviceSelectField(
                        options = deviceOptions,
                        selectedValue = deviceValue,
                        onSelect = { opt ->
                            deviceValue = opt.value
                            deviceName = opt.label.replace(" (offline)", "")
                        },
                        placeholder = "Select device…",
                    )
                    BockTimeField(time, { time = it })
                    BockTextField(volume, { volume = it }, "Volume (optional)")
                    Row {
                        Checkbox(shuffle, { shuffle = it })
                        Text("Shuffle")
                        Spacer(Modifier.width(16.dp))
                        Checkbox(enabled, { enabled = it })
                        Text("Enabled")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val body = buildJsonObject {
                                    put("name", label)
                                    playlistPick.first?.let { put("playlistId", it) }
                                    playlistPick.second?.let { put("playlistName", it) }
                                    put("device", deviceValue)
                                    put("deviceName", deviceName)
                                    put("time", time)
                                    putJsonArray("days") { (0..6).forEach { add(JsonPrimitive(it)) } }
                                    volume.toIntOrNull()?.let { put("volume", it) }
                                    put("shuffle", shuffle)
                                    put("enabled", enabled)
                                }
                                if (editId != null) repository.updateAutomation(editId!!, body)
                                else repository.createAutomation(body)
                                resetForm()
                                load()
                            }
                        }) { Text(if (editId != null) "Update" else "Add automation") }
                        if (editId != null) {
                            TextButton(onClick = { resetForm() }) { Text("Cancel edit") }
                        }
                    }
                }
            }
        }

        if (loading) {
            item { LoadingBox(Modifier.height(120.dp)) }
        } else {
            items(items, key = { it.id }) { auto ->
                ListItem(
                    headlineContent = { Text(auto.name.ifBlank { auto.label }) },
                    supportingContent = {
                        Text(
                            "${formatTime12(auto.time ?: "08:00")} · ${auto.playlistName ?: auto.playlist ?: ""} · ${auto.deviceName ?: auto.device ?: ""}",
                        )
                    },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { scope.launch { repository.runAutomation(auto.id) } }) {
                                Text("Run")
                            }
                            TextButton(onClick = {
                                editId = auto.id
                                label = auto.name.ifBlank { auto.label }
                                val plName = auto.playlistName ?: auto.playlist
                                playlistPick = auto.playlistId to plName
                                playlistSearch = plName ?: ""
                                playlistHits = emptyList()
                                deviceValue = auto.device ?: ""
                                deviceName = auto.deviceName ?: deviceOptions
                                    .find { it.value == auto.device }?.label?.replace(" (offline)", "")
                                    ?: auto.device ?: ""
                                time = auto.time ?: "08:00"
                                volume = auto.volume?.toString() ?: ""
                                shuffle = auto.shuffle
                                enabled = auto.enabled
                            }) { Text("Edit") }
                            TextButton(onClick = {
                                scope.launch { repository.deleteAutomation(auto.id); load() }
                            }) { Text("Delete") }
                        }
                    },
                )
            }
        }
    }
}
