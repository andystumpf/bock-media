package com.bockmedia.console.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.bockVerticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockTextField
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Composable
fun SettingsScreen(
    repository: BockMediaRepository,
    onChangeServer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf<JsonObject?>(null) }
    var publicUrl by remember { mutableStateOf("") }
    var launchPrompt by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<com.bockmedia.console.data.api.dto.AlexaRemoteStatus?>(null) }
    var defaultPlaylist by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            settings = repository.settings()
            val cfg = repository.config()
            publicUrl = cfg["publicUrl"]?.jsonPrimitive?.content ?: ""
            launchPrompt = cfg["launchPlaylistPrompt"]?.jsonPrimitive?.content == "true"
            defaultPlaylist = settings?.get("DefaultPlaylist")?.jsonPrimitive?.content ?: ""
            remote = repository.alexaRemoteStatus()
        }.onFailure { error = it.message ?: "Failed to load settings" }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    LaunchedEffect(remote?.loginStatus) {
        if (remote?.loginStatus == "waiting" || remote?.loginStatus == "starting") {
            while (true) {
                delay(2000)
                remote = repository.alexaLoginState()
                if (remote?.loginStatus == "success" || remote?.authenticated == true) {
                    message = "Alexa login successful"
                    break
                }
                if (remote?.loginStatus == "error" || remote?.loginStatus == "stopped") break
            }
            load()
        }
    }

    Column(Modifier.fillMaxSize().bockVerticalScroll().padding(16.dp)) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LoadingBox() else {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Alexa Remote", style = MaterialTheme.typography.titleSmall)
                    Text("Status: ${if (remote?.authenticated == true) "Connected" else remote?.loginStatus ?: "—"}")
                    remote?.loginUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                remote = repository.alexaLoginStart()
                                remote?.loginUrl?.let { url ->
                                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                                }
                            }
                        }) { Text("Start browser login") }
                        TextButton(onClick = { scope.launch { repository.alexaLoginStop(); load() } }) { Text("Cancel") }
                    }
                }
            }
            BockTextField(defaultPlaylist, { defaultPlaylist = it }, "Default playlist")
            BockTextField(publicUrl, { publicUrl = it }, "Public URL")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(launchPrompt, { launchPrompt = it })
                Text("Launch playlist prompt")
            }
            Button(onClick = {
                scope.launch {
                    error = null
                    message = null
                    runCatching {
                        val settingsResp = repository.saveSettings(buildJsonObject { put("DefaultPlaylist", defaultPlaylist) })
                        if (!settingsResp.ok) error = settingsResp.error ?: "Settings save failed"
                        val configResp = repository.saveConfig(buildJsonObject {
                            put("publicUrl", publicUrl)
                            put("launchPlaylistPrompt", launchPrompt)
                        })
                        if (!configResp.ok) error = configResp.error ?: "Config save failed"
                        if (error == null) message = "Settings saved"
                    }.onFailure { error = it.message ?: "Save failed" }
                }
            }) { Text("Save settings") }
            TextButton(onClick = { scope.launch { repository.clearCache(); message = "Artwork cache cleared" } }) {
                Text("Clear artwork cache")
            }
            TextButton(onClick = onChangeServer) { Text("Change server URL") }
        }
    }
}
