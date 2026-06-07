package com.bockmedia.console.ui.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.LoadingBox
import kotlinx.coroutines.launch

@Composable
fun AnalyticsScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AnalyticsResponse?>(null) }
    var ignored by remember { mutableStateOf<List<String>>(emptyList()) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    suspend fun load() {
        loading = true
        runCatching {
            data = repository.analytics(from.ifBlank { null }, to.ifBlank { null })
            ignored = repository.ignored().items.map { it.path }
        }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(from, { from = it }, label = { Text("From") }, modifier = Modifier.weight(1f))
            OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.weight(1f))
            Button(onClick = { scope.launch { load() } }) { Text("Apply") }
        }
        if (loading) LoadingBox(Modifier.weight(1f)) else {
            LazyColumn(Modifier.weight(1f)) {
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
                items(ignored) { path ->
                    ListItem(
                        headlineContent = { Text(path.substringAfterLast('/')) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch { repository.removeIgnored(path); load() }
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
            Text(row.label ?: row.name ?: "—")
            Text("${row.count}")
        }
    }
}
