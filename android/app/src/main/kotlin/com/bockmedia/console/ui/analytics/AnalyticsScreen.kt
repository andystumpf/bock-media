package com.bockmedia.console.ui.analytics

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.IgnoredTrack
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.LoadingBox
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DatePreset { Last7, Last30, AllTime, Custom }

private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AnalyticsResponse?>(null) }
    var ignored by remember { mutableStateOf<List<IgnoredTrack>>(emptyList()) }
    var preset by remember { mutableStateOf(DatePreset.AllTime) }
    var customFrom by remember { mutableStateOf<LocalDate?>(null) }
    var customTo by remember { mutableStateOf<LocalDate?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    fun dateRange(): Pair<String?, String?> {
        val today = LocalDate.now()
        return when (preset) {
            DatePreset.Last7 -> isoDate.format(today.minusDays(6)) to isoDate.format(today)
            DatePreset.Last30 -> isoDate.format(today.minusDays(29)) to isoDate.format(today)
            DatePreset.AllTime -> null to null
            DatePreset.Custom -> customFrom?.let { isoDate.format(it) } to customTo?.let { isoDate.format(it) }
        }
    }

    suspend fun load() {
        if (data == null) loading = true
        val (from, to) = dateRange()
        runCatching {
            data = repository.analytics(from, to)
            ignored = repository.ignored().items
        }
        loading = false
        refreshing = false
    }

    LaunchedEffect(preset, customFrom, customTo) { load() }

    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom?.toEpochDay()?.times(86_400_000L)
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customFrom = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    preset = DatePreset.Custom
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customTo?.toEpochDay()?.times(86_400_000L)
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customTo = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    preset = DatePreset.Custom
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = preset == DatePreset.Last7,
                onClick = { preset = DatePreset.Last7 },
                label = { Text("Last 7 days") },
            )
            FilterChip(
                selected = preset == DatePreset.Last30,
                onClick = { preset = DatePreset.Last30 },
                label = { Text("Last 30 days") },
            )
            FilterChip(
                selected = preset == DatePreset.AllTime,
                onClick = { preset = DatePreset.AllTime },
                label = { Text("All time") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(onClick = { showFromPicker = true }) {
                Text(customFrom?.format(isoDate) ?: "From")
            }
            OutlinedButton(onClick = { showToPicker = true }) {
                Text(customTo?.format(isoDate) ?: "To")
            }
            TextButton(onClick = {
                preset = DatePreset.AllTime
                customFrom = null
                customTo = null
            }) { Text("Clear") }
            Spacer(Modifier.weight(1f))
            TextButton(
                enabled = !exporting,
                onClick = {
                    scope.launch {
                        exporting = true
                        val (from, to) = dateRange()
                        runCatching {
                            val file = repository.exportAnalyticsCsv(from, to, context.cacheDir)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Export analytics",
                                ),
                            )
                        }
                        exporting = false
                    }
                },
            ) { Text(if (exporting) "Exporting…" else "Export") }
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
                    items(ignored, key = { it.path }) { track ->
                        val title = track.track ?: track.path.substringAfterLast('/')
                        val subtitle = buildString {
                            track.artist?.let { append(it) }
                            track.album?.let {
                                if (isNotEmpty()) append(" · ")
                                append(it)
                            }
                            if (isEmpty()) append(track.path.substringAfterLast('/'))
                            else {
                                append("\n")
                                append(track.path)
                            }
                        }
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(subtitle, maxLines = 2) },
                            trailingContent = {
                                TextButton(onClick = {
                                    scope.launch { repository.removeIgnored(track.path); load() }
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
