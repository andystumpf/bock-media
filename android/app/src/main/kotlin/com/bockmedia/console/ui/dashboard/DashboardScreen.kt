package com.bockmedia.console.ui.dashboard

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PlayButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onPlay: (PlayTarget) -> Unit,
    onNavigateSettings: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<SummaryResponse?>(null) }
    var quick by remember { mutableStateOf<DashboardQuickResponse?>(null) }
    var recent by remember { mutableStateOf<List<RecentPlayRequestItem>>(emptyList()) }
    var health by remember { mutableStateOf<HealthResponse?>(null) }
    var plex by remember { mutableStateOf<PlexSyncStatusResponse?>(null) }
    var remote by remember { mutableStateOf<AlexaRemoteStatus?>(null) }
    val scope = rememberCoroutineScope()

    fun applyBootstrap(data: DashboardBootstrapResponse) {
        summary = data.summary
        quick = data.quick
        recent = data.recent.items
        health = data.health
        plex = data.plexSync
        remote = data.alexaRemote
    }

    suspend fun load(forceRefresh: Boolean = false) {
        if (summary == null) loading = true else refreshing = true
        error = null
        if (!forceRefresh) {
            repository.readStaleDashboard()?.let { applyBootstrap(it); loading = false }
        }
        runCatching {
            applyBootstrap(repository.dashboardBootstrap(forceRefresh))
        }.onFailure {
            if (summary == null) error = it.message
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            runCatching { applyBootstrap(repository.dashboardBootstrap(forceRefresh = true)) }
        }
    }

    when {
        loading && summary == null -> LoadingBox()
        error != null && summary == null -> ErrorText(error!!) { scope.launch { load(forceRefresh = true) } }
        else -> BockLazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    summary?.let {
                        Text("Songs ${it.songs} · Artists ${it.artists} · Albums ${it.albums} · Playlists ${it.playlists}")
                    }
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Service Health", style = MaterialTheme.typography.titleSmall)
                        val h = health
                        Text("Status: ${h?.status ?: "—"}")
                        if (h?.status == "degraded" && h.watchdogFresh != true) {
                            Text(
                                "Health monitor snapshot is stale — server is up but watchdog data is old.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        remote?.let {
                            Text("Alexa remote: ${if (it.authenticated == true) "Connected" else "Not authenticated"}")
                            if (it.configured && it.authenticated != true) {
                                TextButton(onClick = onNavigateSettings) { Text("Re-login in Settings") }
                            }
                        }
                    }
                }
            }
            plex?.let { p ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Plex sync", style = MaterialTheme.typography.titleSmall)
                            Text("Plex tracked: ${p.playlistCount ?: "—"} · Last: ${p.displayLastSuccess ?: p.displayLastRun ?: "—"}")
                        }
                    }
                }
            }
            item { Text("Favorites", style = MaterialTheme.typography.titleMedium) }
            items(quick?.favorites.orEmpty()) { fav ->
                ListItem(
                    headlineContent = { Text(fav.track ?: fav.path) },
                    supportingContent = { Text(fav.artist ?: "") },
                    trailingContent = {
                        if (remoteOk && fav.path.isNotBlank()) {
                            PlayButton(onClick = {
                                onPlay(PlayTarget.Song(fav.path, fav.track ?: "Track"))
                            })
                        }
                    },
                )
            }
            item { Text("Recent play requests", style = MaterialTheme.typography.titleMedium) }
            if (recent.isEmpty()) {
                item {
                    Text(
                        "No recent play requests.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(recent) { r ->
                ListItem(
                    headlineContent = {
                        Text(
                            r.heard ?: "—",
                            color = if (r.success) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                        )
                    },
                    supportingContent = { Text(r.found ?: "") },
                )
            }
        }
    }
}
