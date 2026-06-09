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
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.BockTextField
import com.bockmedia.console.ui.components.LoadingBox
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import kotlinx.coroutines.launch

@Composable
fun AnalyticsScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AnalyticsResponse?>(null) }
    var ignored by remember { mutableStateOf<List<String>>(emptyList()) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        runCatching {
            data = repository.analytics(from.ifBlank { null }, to.ifBlank { null })
            ignored = repository.ignored().items.map { it.path }
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BockTextField(from, { from = it }, "From", modifier = Modifier.weight(1f))
            BockTextField(to, { to = it }, "To", modifier = Modifier.weight(1f))
            Button(onClick = { scope.launch { load() } }) { Text("Apply") }
        }
        if (loading && data == null) {
            LoadingBox(Modifier.weight(1f))
        } else {
            BockPullRefresh(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    scope.launch { load() }
                },
                modifier = Modifier.weight(1f),
            ) {
                BockLazyColumn(Modifier.fillMaxSize()) {
                    data?.let { a ->
                        item {
                            ChartSection("Plays by date", a.byDate.map { (it.label ?: it.name ?: "") to it.count })
                        }
                        item {
                            ChartSection("By hour", a.byHour.map { (it.label ?: it.name ?: "") to it.count })
                        }
                        item {
                            ChartSection("By day of week", a.byDayOfWeek.map { (it.label ?: it.name ?: "") to it.count })
                        }
                        item { StatsSection("Top artists", a.topArtists) }
                        item { StatsSection("Top albums", a.topAlbums) }
                        item { StatsSection("Top tracks", a.topTracks) }
                        item { StatsSection("Top devices", a.topDevices) }
                        item { StatsSection("Genres", a.topGenres) }
                        item { StatsSection("Decades", a.decades) }
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
}

@Composable
private fun ChartSection(title: String, points: List<Pair<String, Int>>) {
    if (points.isEmpty()) return
    val display = points.take(24)
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(display) {
        producer.runTransaction {
            columnSeries {
                series(display.map { it.second.toFloat() })
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberColumnCartesianLayer(),
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(bottom = 8.dp),
    )
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
