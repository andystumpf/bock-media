package com.bockmedia.console.ui.automation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AutomationItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.SearchField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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
    var device by remember { mutableStateOf("") }
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
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(playlistSearch) {
        delay(250)
        if (playlistSearch.length < 1) return@LaunchedEffect
        runCatching {
            playlistHits = repository.playlists(playlistSearch, limit = 25).items.map { it.id to it.name }
        }
    }

    fun resetForm() {
        editId = null
        label = ""
        playlistPick = null to null
        device = ""
        time = "08:00"
        volume = ""
        shuffle = false
        enabled = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Automation", style = MaterialTheme.typography.headlineSmall)
        if (!remoteOk) {
            Text("Configure Alexa remote in Settings first.", color = MaterialTheme.colorScheme.error)
        } else {
            OutlinedTextField(label, { label = it }, label = { Text("Label") }, modifier = Modifier.fillMaxWidth())
            SearchField(playlistSearch, { playlistSearch = it }, "Search playlist")
            playlistHits.take(5).forEach { (id, name) ->
                TextButton(onClick = { playlistPick = id to name }) { Text(name) }
            }
            playlistPick.second?.let { Text("Selected: $it") }
            OutlinedTextField(device, { device = it }, label = { Text("Device serial or group:…") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(time, { time = it }, label = { Text("Time HH:MM") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(volume, { volume = it }, label = { Text("Volume (optional)") }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(shuffle, { shuffle = it })
                Text("Shuffle")
                Checkbox(enabled, { enabled = it })
                Text("Enabled")
            }
            Button(onClick = {
                scope.launch {
                    val body = buildJsonObject {
                        put("name", label)
                        playlistPick.first?.let { put("playlistId", it) }
                        playlistPick.second?.let { put("playlist", it) }
                        put("device", device)
                        put("time", time)
                        volume.toIntOrNull()?.let { put("volume", it) }
                        put("shuffle", shuffle)
                        put("enabled", enabled)
                        put("repeat", "daily")
                    }
                    if (editId != null) repository.updateAutomation(editId!!, body)
                    else repository.createAutomation(body)
                    resetForm()
                    load()
                }
            }) { Text(if (editId != null) "Update" else "Add automation") }
            if (editId != null) TextButton(onClick = { resetForm() }) { Text("Cancel edit") }
        }
        Spacer(Modifier.height(16.dp))
        if (loading) LoadingBox() else {
            LazyColumn {
                items(items) { auto ->
                    ListItem(
                        headlineContent = { Text(auto.name.ifBlank { auto.label }) },
                        supportingContent = { Text("${auto.time} · ${auto.playlist ?: ""} · ${auto.device ?: ""}") },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { scope.launch { repository.runAutomation(auto.id) } }) { Text("Run") }
                                TextButton(onClick = {
                                    editId = auto.id
                                    label = auto.name.ifBlank { auto.label }
                                    playlistPick = auto.playlistId to (auto.playlistName ?: auto.playlist)
                                    device = auto.device ?: ""
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
}
