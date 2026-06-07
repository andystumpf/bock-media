package com.bockmedia.console.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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

    suspend fun load() {
        loading = true
        runCatching {
            settings = repository.settings().settings
            val cfg = repository.config().config
            publicUrl = cfg?.get("publicUrl")?.jsonPrimitive?.content ?: ""
            launchPrompt = cfg?.get("launchPlaylistPrompt")?.jsonPrimitive?.content == "true"
            defaultPlaylist = settings?.get("DefaultPlaylist")?.jsonPrimitive?.content ?: ""
            remote = repository.alexaRemoteStatus()
        }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
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
            OutlinedTextField(defaultPlaylist, { defaultPlaylist = it }, label = { Text("Default playlist") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(publicUrl, { publicUrl = it }, label = { Text("Public URL") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(launchPrompt, { launchPrompt = it })
                Text("Launch playlist prompt")
            }
            Button(onClick = {
                scope.launch {
                    repository.saveSettings(buildJsonObject { put("DefaultPlaylist", defaultPlaylist) })
                    repository.saveConfig(buildJsonObject {
                        put("publicUrl", publicUrl)
                        put("launchPlaylistPrompt", launchPrompt)
                    })
                    message = "Settings saved"
                }
            }) { Text("Save settings") }
            TextButton(onClick = { scope.launch { repository.clearCache(); message = "Artwork cache cleared" } }) {
                Text("Clear artwork cache")
            }
            TextButton(onClick = onChangeServer) { Text("Change server URL") }
        }
    }
}
