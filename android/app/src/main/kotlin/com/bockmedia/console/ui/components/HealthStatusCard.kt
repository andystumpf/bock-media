package com.bockmedia.console.ui.components

import android.net.Uri
import com.bockmedia.console.ui.theme.BockGreen
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AlexaRemoteStatus
import com.bockmedia.console.data.api.dto.HealthResponse
import com.bockmedia.console.data.api.dto.PlexSyncStatusResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val WarnAmber = Color(0xFFE8A838)
private val ErrRed = Color(0xFFE74C3C)

@Composable
fun HealthStatusCard(
    repository: BockMediaRepository,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<HealthResponse?>(null) }
    var plex by remember { mutableStateOf<PlexSyncStatusResponse?>(null) }
    var alexaRemote by remember { mutableStateOf<AlexaRemoteStatus?>(null) }
    var connection by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true
        loadError = null
        connection = runCatching { repository.connectionSummary() }.getOrNull()
        health = runCatching { repository.health() }.getOrElse {
            loadError = "Could not reach server"
            null
        }
        plex = runCatching { repository.plexSyncStatus() }.getOrNull()
        alexaRemote = runCatching { repository.alexaRemoteStatus() }.getOrNull()
        loading = false
    }

    LaunchedEffect(repository) { load() }

    LaunchedEffect(alexaRemote?.loginStatus) {
        val status = alexaRemote?.loginStatus ?: return@LaunchedEffect
        if (status != "waiting" && status != "starting") return@LaunchedEffect
        while (true) {
            delay(2000)
            alexaRemote = runCatching { repository.alexaLoginState() }.getOrNull()
            if (alexaRemote?.loginStatus == "success" || alexaRemote?.authenticated == true) {
                onMessage("Alexa login successful")
                load()
                break
            }
            if (alexaRemote?.loginStatus == "error" || alexaRemote?.loginStatus == "stopped") break
        }
    }

    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Server health", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                IconButton(
                    onClick = { scope.launch { load() } },
                    enabled = !loading,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }

            connection?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when {
                loading && health == null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BockProgressIndicator(size = 32.dp)
                    }
                }
                loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                health != null -> HealthDetails(health!!, plex)
            }

            alexaRemote?.let { status ->
                AlexaRemoteSection(
                    remote = status,
                    onStartLogin = {
                        scope.launch {
                            alexaRemote = repository.alexaLoginStart()
                            alexaRemote?.loginUrl?.let { url ->
                                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                            }
                        }
                    },
                    onCancelLogin = {
                        scope.launch {
                            repository.alexaLoginStop()
                            load()
                        }
                    },
                )
            }

            TextButton(onClick = {
                scope.launch {
                    repository.clearCache()
                    repository.clearCaches()
                    onMessage("Server artwork cache cleared")
                }
            }) {
                Text("Clear server artwork cache")
            }
        }
    }
}

@Composable
private fun HealthDetails(health: HealthResponse, plex: PlexSyncStatusResponse?) {
    val stale = health.watchdogFresh == false
    if (stale) {
        Text(
            "Watchdog snapshot stale (${formatAgo(health.watchdogAgeSeconds)}) — metrics may be outdated",
            style = MaterialTheme.typography.labelSmall,
            color = WarnAmber,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Uptime", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatAgo(health.uptimeSeconds), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Last Alexa request", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatAgo(health.lastAlexaHitAgo), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }

    Spacer(Modifier.height(4.dp))
    HealthChip("Backend service", health.backend, "ourmedia systemd unit")
    HealthChip("Backend HTTP", health.backendHttp, "Local Flask /api/summary")
    HealthChip("Tunnel service", health.tunnel, "Cloudflare tunnel unit")
    val tunnelDetail = buildString {
        health.publicLatencyMs?.let { append("${it}ms") }
        health.publicStatus?.let {
            if (isNotEmpty()) append(", ")
            append("HTTP $it")
        }
        if (isEmpty()) append("Public /alexa endpoint")
    }
    HealthChip("Public endpoint", health.tunnelReachable, tunnelDetail)
    HealthChip("Alexa session", health.alexaAuth, "alexapy login (Play on device)")
    HealthChip("Skill testing", skillTestingState(health.skillTesting), "Developer testing mode")
    if (health.plexConfigured == true) {
        HealthChip("Plex sync", health.plexReachable, "Plex playlist sync")
    }
    plex?.let { p ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Last Plex sync", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                when {
                    p.running -> "Running…"
                    !p.lastSuccess.isNullOrBlank() -> p.lastSuccess
                    !p.lastRun.isNullOrBlank() -> p.lastRun
                    else -> "—"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AlexaRemoteSection(
    remote: AlexaRemoteStatus,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text("Alexa remote", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    when {
        remote.authenticated == true -> Text("Connected — Play on device and volume controls available", color = BockGreen)
        remote.configured != true -> Text("Not configured on server", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val status = remote.loginStatus ?: "needs login"
            Text("Status: $status", color = WarnAmber)
            remote.loginError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStartLogin) { Text("Start browser login") }
                if (status == "waiting" || status == "starting") {
                    TextButton(onClick = onCancelLogin) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun HealthChip(label: String, ok: Boolean?, detail: String) {
    val (color, value) = when (ok) {
        true -> BockGreen to "OK"
        false -> ErrRed to "Down"
        null -> MaterialTheme.colorScheme.onSurfaceVariant to "Unknown"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = color)
        }
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun skillTestingState(raw: kotlinx.serialization.json.JsonElement?): Boolean? {
    val primitive = raw as? JsonPrimitive ?: return null
    return primitive.booleanOrNull
}

private fun formatAgo(seconds: Int?): String {
    if (seconds == null) return "—"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}
