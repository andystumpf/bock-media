package com.bockmedia.console.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaRemoteStatus
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockProgressIndicator
import com.bockmedia.console.ui.effectiveLoginError
import com.bockmedia.console.ui.effectiveLoginStatus
import com.bockmedia.console.ui.effectiveLoginUrl
import com.bockmedia.console.ui.theme.BockGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WarnAmber = Color(0xFFE8A838)

/** Prominent Alexa sign-in — always visible in Settings (not buried in server health). */
@Composable
fun AlexaRemoteCard(
    repository: BockMediaRepository,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<AlexaRemoteStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var statusError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        statusError = null
        remote = runCatching { repository.alexaRemoteStatus(probe = true) }
            .onFailure { statusError = it.message ?: "Can't reach Alexa remote status" }
            .getOrNull()
        loading = false
    }

    fun startLogin() {
        scope.launch {
            runCatching {
                remote = repository.alexaLoginStart()
                for (i in 0 until 20) {
                    val st = remote?.effectiveLoginStatus()
                    if (st == "error" || st == "stopped") break
                    if (remote?.portReady == true || st == "waiting") break
                    delay(500)
                    remote = repository.alexaLoginState()
                }
                val url = remote?.effectiveLoginUrl()
                if (url != null && remote?.effectiveLoginStatus() !in setOf("error", "stopped")) {
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                    onMessage("Sign in to Amazon in the browser, then return here.")
                } else {
                    onMessage(
                        remote?.effectiveLoginError()
                            ?: "Login page did not start. Connect to home Wi‑Fi and try again.",
                    )
                }
            }.onFailure {
                onMessage(it.message ?: "Could not start Alexa login")
            }
        }
    }

    LaunchedEffect(repository) { load() }

    LaunchedEffect(remote?.effectiveLoginStatus()) {
        val status = remote?.effectiveLoginStatus() ?: return@LaunchedEffect
        if (status != "waiting" && status != "starting") return@LaunchedEffect
        while (true) {
            delay(2000)
            remote = runCatching { repository.alexaLoginState() }.getOrNull()
            if (remote?.effectiveLoginStatus() == "success" || remote?.authenticated == true) {
                onMessage("Alexa login successful")
                load()
                break
            }
            if (remote?.effectiveLoginStatus() in setOf("error", "stopped")) break
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speaker, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Alexa remote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { scope.launch { load() } }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }

            when {
                loading && remote == null -> {
                    BockProgressIndicator(size = 32.dp)
                }
                statusError != null && remote == null -> {
                    Text(
                        statusError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Check server connection in Settings, then tap Refresh or sign in below.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startLogin() }) { Text("Start browser login") }
                        TextButton(onClick = { scope.launch { load() } }) { Text("Refresh") }
                    }
                }
                remote?.authenticated == true -> {
                    Text(
                        "Connected — Play on device and volume controls available.",
                        color = BockGreen,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { startLogin() }) { Text("Sign in again") }
                }
                remote?.configured != true -> {
                    Text(
                        "Not configured on the server — add alexaRemote.email to config.json.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    val status = remote?.effectiveLoginStatus() ?: "needs login"
                    Text("Status: $status", color = WarnAmber, style = MaterialTheme.typography.bodySmall)
                    remote?.effectiveLoginError()?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    remote?.effectiveLoginUrl()?.let { url ->
                        Text(
                            "Login page: $url",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Connect to home Wi‑Fi, then tap Start browser login and sign in to Amazon.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { startLogin() }) { Text("Start browser login") }
                        if (status == "waiting" || status == "starting") {
                            TextButton(onClick = {
                                scope.launch {
                                    repository.alexaLoginStop()
                                    load()
                                }
                            }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }
}
