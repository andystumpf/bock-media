package com.bockmedia.console.ui.analytics

import androidx.compose.foundation.layout.*
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockTextField
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun AnalyticsScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AnalyticsResponse?>(null) }
    var ignored by remember { mutableStateOf<List<com.bockmedia.console.data.api.dto.IgnoredTrack>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    suspend fun load() {
        loading = true
        error = null
        runCatching {
            data = repository.analytics(from.ifBlank { null }, to.ifBlank { null })
            ignored = repository.ignored().items
        }.onFailure { error = it.message ?: "Failed to load analytics" }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BockTextField(from, { from = it }, "From", modifier = Modifier.weight(1f))
            BockTextField(to, { to = it }, "To", modifier = Modifier.weight(1f))
            Button(onClick = { scope.launch { load() } }) { Text("Apply") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            BockLazyColumn(Modifier.weight(1f)) {
                data?.let { a ->
                    item { StatsSection("Top artists", a.topArtists) }
                    item { StatsSection("Top albums", a.topAlbums) }
                    item { StatsSection("Top tracks", a.topTracks) }
                    item { StatsSection("Top devices", a.topDevices) }
                    item { StatsSection("Genres", a.topGenres) }
                    item { StatsSection("By hour", a.byHour) }
                    item { StatsSection("By day of week", a.byDayOfWeek) }
                }
                item { Text("Never play again", style = MaterialTheme.typography.titleMedium) }
                items(ignored) { row ->
                    ListItem(
                        headlineContent = { Text(row.track ?: row.path.substringAfterLast('/')) },
                        supportingContent = { row.artist?.let { Text(it) } },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { repository.removeIgnored(row.path) }
                                        .onSuccess { load() }
                                        .onFailure { error = it.message ?: "Failed to remove" }
                                }
                            }) { Text("Allow again") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsSection(title: String, rows: List<CountRow>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    rows.take(10).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.label ?: row.name ?: row.day ?: row.decade ?: row.hour?.toString() ?: "—")
            Text("${row.count}")
        }
    }
}
