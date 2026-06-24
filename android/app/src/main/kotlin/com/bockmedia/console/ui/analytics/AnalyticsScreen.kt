package com.bockmedia.console.ui.analytics

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.api.dto.displayName
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.theme.*
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal enum class DatePreset { Last7, Last30, AllTime, Custom }
private enum class ActivityPeriod { Day, Week, Month, Year }

private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
private val intFmt = NumberFormat.getIntegerInstance()

internal fun analyticsDateRange(
    preset: DatePreset,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    today: LocalDate = LocalDate.now(),
): Pair<String?, String?> = when (preset) {
    DatePreset.Last7 -> isoDate.format(today.minusDays(6)) to isoDate.format(today)
    DatePreset.Last30 -> isoDate.format(today.minusDays(29)) to isoDate.format(today)
    DatePreset.AllTime -> null to null
    DatePreset.Custom -> customFrom?.let { isoDate.format(it) } to customTo?.let { isoDate.format(it) }
}

internal fun analyticsRangeKey(
    preset: DatePreset,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    deviceId: String? = null,
): String {
    val range = when (preset) {
        DatePreset.Last7 -> "last7"
        DatePreset.Last30 -> "last30"
        DatePreset.AllTime -> "all"
        DatePreset.Custom -> "custom:${customFrom ?: "_"}:${customTo ?: "_"}"
    }
    val device = deviceId?.takeIf { it.isNotBlank() } ?: "all-devices"
    return "$range|$device"
}

internal sealed class AnalyticsDeviceFilter {
    data object AllDevices : AnalyticsDeviceFilter()
    data object ThisPhone : AnalyticsDeviceFilter()
    data class Specific(val deviceId: String, val label: String) : AnalyticsDeviceFilter()
}

internal fun AnalyticsDeviceFilter.apiDeviceId(thisPhoneId: String): String? = when (this) {
    AnalyticsDeviceFilter.AllDevices -> null
    AnalyticsDeviceFilter.ThisPhone -> thisPhoneId.takeIf { it.isNotBlank() }
    is AnalyticsDeviceFilter.Specific -> deviceId
}

internal fun AnalyticsDeviceFilter.displayLabel(thisPhoneId: String): String = when (this) {
    AnalyticsDeviceFilter.AllDevices -> "All devices"
    AnalyticsDeviceFilter.ThisPhone -> "This phone"
    is AnalyticsDeviceFilter.Specific -> label.ifBlank { deviceId.takeLast(8) }
}

private fun localDateToPickerMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun pickerMillisToLocalDate(ms: Long): LocalDate =
    Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(repository: BockMediaRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<AnalyticsResponse?>(null) }
    var ignored by remember { mutableStateOf<List<IgnoredTrack>>(emptyList()) }
    var preset by remember { mutableStateOf(DatePreset.Last30) }
    var activityPeriod by remember { mutableStateOf(ActivityPeriod.Day) }
    var customFrom by remember { mutableStateOf<LocalDate?>(null) }
    var customTo by remember { mutableStateOf<LocalDate?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val thisPhoneId = remember(repository) { repository.clientDeviceId() }
    var deviceFilter by remember { mutableStateOf<AnalyticsDeviceFilter>(AnalyticsDeviceFilter.AllDevices) }
    var knownDevices by remember { mutableStateOf<List<DeviceItem>>(emptyList()) }
    var showDeviceMenu by remember { mutableStateOf(false) }

    val queryKey = analyticsRangeKey(
        preset,
        customFrom,
        customTo,
        deviceFilter.apiDeviceId(thisPhoneId),
    )

    suspend fun loadAnalytics() {
        if (data == null) loading = true else refreshing = true
        val (from, to) = analyticsDateRange(preset, customFrom, customTo)
        val deviceId = deviceFilter.apiDeviceId(thisPhoneId)
        try {
            data = repository.analytics(from, to, deviceId)
            ignored = repository.ignored().items
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the previous snapshot when a ranged reload fails.
        } finally {
            loading = false
            refreshing = false
        }
    }

    LaunchedEffect(Unit) {
        runCatching { knownDevices = repository.devices() }
    }

    LaunchedEffect(queryKey) {
        loadAnalytics()
    }

    val onExportClick: () -> Unit = {
        scope.launch {
            exporting = true
            val (from, to) = analyticsDateRange(preset, customFrom, customTo)
            val deviceId = deviceFilter.apiDeviceId(thisPhoneId)
            runCatching {
                val file = repository.exportAnalyticsCsv(from, to, context.cacheDir, deviceId)
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
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom?.let { localDateToPickerMillis(it) }
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customFrom = pickerMillisToLocalDate(ms)
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
            initialSelectedDateMillis = customTo?.let { localDateToPickerMillis(it) }
                ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customTo = pickerMillisToLocalDate(ms)
                    }
                    preset = DatePreset.Custom
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    Column(Modifier.fillMaxSize()) {
        AnalyticsToolbar(
            preset = preset,
            customFrom = customFrom,
            customTo = customTo,
            deviceFilter = deviceFilter,
            deviceMenuExpanded = showDeviceMenu,
            knownDevices = knownDevices,
            thisPhoneId = thisPhoneId,
            exporting = exporting,
            onPreset = { next ->
                preset = next
                if (next != DatePreset.Custom) {
                    customFrom = null
                    customTo = null
                }
            },
            onDeviceMenuExpanded = { showDeviceMenu = it },
            onDeviceFilter = {
                deviceFilter = it
                showDeviceMenu = false
            },
            onFrom = { showFromPicker = true },
            onTo = { showToPicker = true },
            onClear = {
                preset = DatePreset.AllTime
                customFrom = null
                customTo = null
            },
            onExport = onExportClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (loading && data == null) {
            LoadingBox(Modifier.weight(1f))
        } else {
            BockPullRefresh(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { loadAnalytics() } },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                BockLazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val deviceScoped = deviceFilter !is AnalyticsDeviceFilter.AllDevices
                    data?.let { a ->
                        item(key = "stats-$queryKey") { SummaryStatsGrid(a) }
                        item(key = "activity-$queryKey") {
                            ActivityChartCard(
                                data = a,
                                period = activityPeriod,
                                onPeriod = { activityPeriod = it },
                            )
                        }
                        item(key = "hour-dow-$queryKey") {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AnalyticsCard(title = "Hour of day") {
                                    HourOfDayChart(a.hourOfDay)
                                }
                                AnalyticsCard(title = "Day of week") {
                                    DayOfWeekChart(a.dayOfWeek)
                                }
                            }
                        }
                        if (!a.heatmap.isNullOrEmpty()) {
                            item(key = "heatmap-$queryKey") { HeatmapCard(a.heatmap!!) }
                        }
                        item(key = "artists-$queryKey") { RankingCard("Top artists", a.topArtists, BockGreen) }
                        item(key = "albums-$queryKey") { RankingCard("Top albums", a.topAlbums, BockGold) }
                        item(key = "tracks-$queryKey") { RankingCard("Top tracks", a.topTracks, Color(0xFF509BF5)) }
                        if (!deviceScoped) {
                            item(key = "devices-top-$queryKey") { RankingCard("Top devices", a.topDevices, BockNavy) }
                        }
                        if (a.topGenres.isNotEmpty()) {
                            item(key = "genres-$queryKey") { RankingCard("Top genres", a.topGenres, Color(0xFF8D67AB)) }
                        }
                        if (a.topDecades.isNotEmpty()) {
                            item(key = "decades-$queryKey") {
                                AnalyticsCard("By decade") {
                                    DecadeChart(a.topDecades)
                                }
                            }
                        }
                    }
                    if (!deviceScoped && data?.deviceBreakdown?.any { it.plays + it.downloads + it.connects > 0 } == true) {
                        item(key = "devices-$queryKey") {
                            DeviceBreakdownCard(data!!.deviceBreakdown)
                        }
                    }
                    item {
                        Text(
                            "Never play again",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (ignored.isEmpty()) {
                        item {
                            Text(
                                "No ignored tracks. Block a song from Now Playing to add it here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BockMuted,
                            )
                        }
                    } else {
                        items(ignored, key = { it.path }) { track ->
                            IgnoredTrackRow(
                                track = track,
                                onAllow = {
                                    scope.launch {
                                        repository.removeIgnored(track.path)
                                        loadAnalytics()
                                    }
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsToolbar(
    preset: DatePreset,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    deviceFilter: AnalyticsDeviceFilter,
    deviceMenuExpanded: Boolean,
    knownDevices: List<DeviceItem>,
    thisPhoneId: String,
    exporting: Boolean,
    onPreset: (DatePreset) -> Unit,
    onDeviceMenuExpanded: (Boolean) -> Unit,
    onDeviceFilter: (AnalyticsDeviceFilter) -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thisPhoneName = knownDevices.firstOrNull { it.deviceId == thisPhoneId }?.name
    val otherDevices = knownDevices
        .filter { it.deviceId.isNotBlank() && it.deviceId != thisPhoneId }
        .sortedBy { it.name?.lowercase() ?: it.deviceId }
    val deviceLabel = when (deviceFilter) {
        AnalyticsDeviceFilter.ThisPhone -> thisPhoneName?.let { "This phone ($it)" } ?: "This phone"
        else -> deviceFilter.displayLabel(thisPhoneId)
    }
    val presetScroll = rememberScrollState()
    val dateScroll = rememberScrollState()

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).widthIn(min = 0.dp)) {
                OutlinedButton(
                    onClick = { onDeviceMenuExpanded(true) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        deviceLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = deviceMenuExpanded,
                    onDismissRequest = { onDeviceMenuExpanded(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("All devices") },
                        onClick = { onDeviceFilter(AnalyticsDeviceFilter.AllDevices) },
                        leadingIcon = {
                            if (deviceFilter is AnalyticsDeviceFilter.AllDevices) {
                                Icon(Icons.Default.Check, null, tint = BockGreen)
                            }
                        },
                    )
                    if (thisPhoneId.isNotBlank()) {
                        DropdownMenuItem(
                            text = {
                                Text(thisPhoneName?.let { "This phone ($it)" } ?: "This phone")
                            },
                            onClick = { onDeviceFilter(AnalyticsDeviceFilter.ThisPhone) },
                            leadingIcon = {
                                if (deviceFilter is AnalyticsDeviceFilter.ThisPhone) {
                                    Icon(Icons.Default.Check, null, tint = BockGreen)
                                } else {
                                    Icon(Icons.Default.PhoneAndroid, null, tint = BockMuted)
                                }
                            },
                        )
                    }
                    otherDevices.forEach { device ->
                        val selected = deviceFilter is AnalyticsDeviceFilter.Specific &&
                            deviceFilter.deviceId == device.deviceId
                        DropdownMenuItem(
                            text = {
                                Text(
                                    device.name?.takeIf { it.isNotBlank() } ?: device.deviceId.takeLast(8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                onDeviceFilter(
                                    AnalyticsDeviceFilter.Specific(
                                        deviceId = device.deviceId,
                                        label = device.name?.takeIf { it.isNotBlank() }
                                            ?: device.deviceId.takeLast(8),
                                    ),
                                )
                            },
                            leadingIcon = {
                                if (selected) {
                                    Icon(Icons.Default.Check, null, tint = BockGreen)
                                }
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onExport, enabled = !exporting) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = if (exporting) "Exporting" else "Export analytics",
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(presetScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                DatePreset.Last7 to "7 days",
                DatePreset.Last30 to "30 days",
                DatePreset.AllTime to "All time",
            ).forEach { (p, label) ->
                FilterChip(
                    selected = preset == p,
                    onClick = { onPreset(p) },
                    label = { Text(label, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BockGreen,
                        selectedLabelColor = Color(0xFF0F1419),
                    ),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(dateScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onFrom, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(customFrom?.format(isoDate) ?: "From", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onTo, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(customTo?.format(isoDate) ?: "To", style = MaterialTheme.typography.labelLarge)
            }
            if (preset == DatePreset.Custom || customFrom != null || customTo != null) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SummaryStatsGrid(data: AnalyticsResponse) {
    val streak = data.listeningStreak ?: ListeningStreak(data.currentStreak, data.longestStreak)
    val cov = data.catalogCoverage
    val rr = data.repeatRate
    val tiles = buildList {
        add(
            StatTile(
                Icons.Default.Headphones,
                Color(0xFF509BF5),
                intFmt.format(data.totalPlays),
                "Total plays",
                "${intFmt.format(data.uniqueArtists)} artists",
            ),
        )
        add(
            StatTile(
                Icons.Default.LocalFireDepartment,
                BockGold,
                "${streak.current}",
                "Day streak",
                "Best: ${streak.longest}",
            ),
        )
        add(
            StatTile(
                Icons.Default.Album,
                Color(0xFF8D67AB),
                if (cov != null && cov.pct < 0.1 && cov.heard > 0) "<0.1%" else "${cov?.pct ?: 0}%",
                "Catalog heard",
                cov?.let { "${intFmt.format(it.heard)} / ${intFmt.format(it.total)}" },
            ),
        )
        add(
            StatTile(
                Icons.Default.Repeat,
                BockGreen,
                "${rr?.pct ?: 0}%",
                "Repeat rate",
                rr?.let { "${intFmt.format(it.repeated)} replays" },
            ),
        )
        data.mostActiveDay?.let { mad ->
            add(
                StatTile(
                    Icons.Default.CalendarMonth,
                    Color(0xFFE91429),
                    intFmt.format(mad.count),
                    "Best day",
                    mad.date,
                ),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { tile ->
                    StatCard(
                        icon = tile.icon,
                        tint = tile.tint,
                        value = tile.value,
                        label = tile.label,
                        subtitle = tile.subtitle,
                        modifier = Modifier.weight(1f).widthIn(min = 0.dp),
                    )
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

private data class StatTile(
    val icon: ImageVector,
    val tint: Color,
    val value: String,
    val label: String,
    val subtitle: String?,
)

@Composable
private fun StatCard(
    icon: ImageVector,
    tint: Color,
    value: String,
    label: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SpotifyElevated,
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = BockMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = BockMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AnalyticsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SpotifyElevated,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ActivityChartCard(
    data: AnalyticsResponse,
    period: ActivityPeriod,
    onPeriod: (ActivityPeriod) -> Unit,
) {
    val points = when (period) {
        ActivityPeriod.Day -> data.activity?.day.orEmpty()
        ActivityPeriod.Week -> data.activity?.week.orEmpty()
        ActivityPeriod.Month -> data.activity?.month.orEmpty()
        ActivityPeriod.Year -> data.activity?.year.orEmpty()
    }
    AnalyticsCard("Activity over time") {
        val periodScroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(periodScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ActivityPeriod.entries.forEach { p ->
                FilterChip(
                    selected = period == p,
                    onClick = { onPeriod(p) },
                    label = {
                        Text(
                            when (p) {
                                ActivityPeriod.Day -> "Day"
                                ActivityPeriod.Week -> "Week"
                                ActivityPeriod.Month -> "Month"
                                ActivityPeriod.Year -> "Year"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BockGreen.copy(alpha = 0.25f),
                        selectedLabelColor = BockGreen,
                    ),
                )
            }
        }
        if (points.isEmpty() || points.all { it.count == 0 }) {
            Text("No plays in this range", color = BockMuted, style = MaterialTheme.typography.bodyMedium)
        } else {
            LineChart(points.map { it.count }, height = 200.dp)
        }
    }
}

@Composable
private fun LineChart(values: List<Int>, height: androidx.compose.ui.unit.Dp) {
    val display = values.takeLast(48)
    val chartKey = display.joinToString(",")
    val producer = remember(chartKey) { CartesianChartModelProducer() }
    LaunchedEffect(chartKey) {
        producer.runTransaction {
            lineSeries { series(display.map { it.toFloat() }) }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(),
            startAxis = rememberStartAxis(),
            bottomAxis = rememberBottomAxis(),
        ),
        modelProducer = producer,
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    )
}

@Composable
private fun ColumnChart(values: List<Int>, height: androidx.compose.ui.unit.Dp, color: Color = BockGreen) {
    if (values.isEmpty()) return
    val chartKey = values.joinToString(",")
    val producer = remember(chartKey) { CartesianChartModelProducer() }
    LaunchedEffect(chartKey) {
        producer.runTransaction {
            columnSeries { series(values.map { it.toFloat() }) }
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
            .height(height),
    )
}

@Composable
private fun HourOfDayChart(hours: List<HourCount>) {
    val values = (0 until 24).map { h -> hours.find { it.hour == h }?.count ?: 0 }
    ColumnChart(values, height = 140.dp)
}

@Composable
private fun DayOfWeekChart(days: List<DayCount>) {
    val order = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val values = order.map { d -> days.find { it.day.equals(d, true) }?.count ?: 0 }
    ColumnChart(values, height = 140.dp, color = BockGold)
}

@Composable
private fun DecadeChart(decades: List<DecadeRow>) {
    ColumnChart(decades.map { it.count }, height = 160.dp, color = Color(0xFF8D67AB))
    val decadeScroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(decadeScroll),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        decades.take(8).forEach { d ->
            Text(
                d.decade ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = BockMuted,
            )
        }
    }
}

@Composable
private fun HeatmapCard(matrix: List<List<Int>>) {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxVal = matrix.flatMap { it }.maxOrNull()?.coerceAtLeast(1) ?: 1
    AnalyticsCard("Listening heatmap") {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(28.dp))
            days.forEach { d ->
                Text(
                    d,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = BockMuted,
                )
            }
        }
        matrix.forEachIndexed { hour, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hourLabel(hour),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = BockMuted,
                )
                row.forEach { v ->
                    val alpha = if (v > 0) 0.15f + (v.toFloat() / maxVal) * 0.85f else 0f
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1.4f)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (v > 0) BockGreen.copy(alpha = alpha) else SpotifyShortcutHover,
                            ),
                    )
                }
            }
        }
    }
}

private fun hourLabel(h: Int): String = when {
    h == 0 -> "12a"
    h < 12 -> "${h}a"
    h == 12 -> "12p"
    else -> "${h - 12}p"
}

@Composable
private fun RankingCard(title: String, rows: List<CountRow>, accent: Color) {
    if (rows.isEmpty()) return
    val items = rows.take(8)
    val max = items.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    AnalyticsCard(title) {
        items.forEachIndexed { index, row ->
            val subtitle = row.artist?.takeIf { it.isNotBlank() }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        modifier = Modifier.width(20.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.displayName(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        subtitle?.let {
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = BockMuted)
                        }
                    }
                    Text(intFmt.format(row.count), style = MaterialTheme.typography.labelLarge, color = BockMuted)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(SpotifyShortcutHover),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.count.toFloat() / max)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accent),
                    )
                }
            }
            if (index < items.lastIndex) Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun DeviceBreakdownCard(devices: List<DeviceBreakdownRow>) {
    val active = devices
        .filter { it.plays + it.downloads + it.connects > 0 }
        .sortedByDescending { it.plays + it.downloads }
        .take(6)
    if (active.isEmpty()) return
    AnalyticsCard("Device Activity") {
        active.forEach { d ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        d.name.ifBlank { d.deviceId.takeLast(8) },
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(d.platform, style = MaterialTheme.typography.labelSmall, color = BockMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (d.plays > 0) MiniStat("${d.plays}", "plays")
                    if (d.downloads > 0) MiniStat("${d.downloads}", "dl")
                    if (d.connects > 0) MiniStat("${d.connects}", "conn")
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = BockMuted)
    }
}

@Composable
private fun IgnoredTrackRow(track: IgnoredTrack, onAllow: () -> Unit) {
    val title = track.track ?: track.path.substringAfterLast('/')
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SpotifyElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val sub = buildString {
                    track.artist?.let { append(it) }
                    track.album?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }
                if (sub.isNotBlank()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, color = BockMuted)
            },
            trailingContent = {
                TextButton(onClick = onAllow) { Text("Allow") }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
