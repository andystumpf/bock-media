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
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PlayButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    repository: BockMediaRepository,
    onPlay: (PlayTarget) -> Unit,
    onNavigateSettings: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<SummaryResponse?>(null) }
    var quick by remember { mutableStateOf<DashboardQuickResponse?>(null) }
    var recent by remember { mutableStateOf<List<RecentPlayRequestItem>>(emptyList()) }
    var health by remember { mutableStateOf<HealthResponse?>(null) }
    var plex by remember { mutableStateOf<PlexSyncStatusResponse?>(null) }
    var remote by remember { mutableStateOf<AlexaRemoteStatus?>(null) }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = summary == null
        error = null
        runCatching {
            summary = repository.summary()
            quick = repository.dashboardQuick()
            recent = repository.recent(1, 10).items
            health = repository.health()
            plex = repository.plexSyncStatus()
            remote = repository.alexaRemoteStatus()
        }.onFailure { error = it.message }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            runCatching { health = repository.health(); remote = repository.alexaRemoteStatus() }
        }
    }

    when {
        loading && summary == null -> LoadingBox()
        error != null && summary == null -> ErrorText(error!!) { scope.launch { load() } }
        else -> BockPullRefresh(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; scope.launch { load() } },
            modifier = Modifier.fillMaxSize(),
        ) {
        BockLazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                summary?.let {
                    Text("Songs ${it.songs} · Artists ${it.artists} · Albums ${it.albums} · Playlists ${it.playlists}")
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Service Health", style = MaterialTheme.typography.titleSmall)
                        Text("Status: ${health?.status ?: "—"}")
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
                            Text("Playlists: ${p.playlistCount ?: "—"} · Last: ${p.lastSuccess ?: "—"}")
                        }
                    }
                }
            }
            item { Text("Favorites", style = MaterialTheme.typography.titleMedium) }
            items(quick?.favorites.orEmpty()) { fav ->
                ListItem(
                    headlineContent = { Text(fav.track ?: fav.path) },
                    supportingContent = { Text(fav.artist ?: "") },
                    trailingContent = { PlayButton(onClick = { onPlay(PlayTarget.Song(fav.path, fav.track ?: "Track")) }) },
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
}
