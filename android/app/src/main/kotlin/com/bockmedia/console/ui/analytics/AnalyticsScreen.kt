package com.bockmedia.console.ui.analytics

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.testing.BockTestTags
import com.bockmedia.console.ui.theme.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class DatePreset { Last7, Last30, AllTime, Custom }
internal enum class ActivityPeriod { Day, Week, Month, Year }

private val isoDate = DateTimeFormatter.ISO_LOCAL_DATE
private val displayDate = DateTimeFormatter.ofPattern("MM/dd/yyyy")
private val intFmt = NumberFormat.getIntegerInstance()

internal fun formatAnalyticsPct(value: Double, maxDecimals: Int = 1): String {
    var text = "%.${maxDecimals}f".format(Locale.US, value)
    if (maxDecimals > 1) {
        text = text.trimEnd('0').trimEnd('.')
    }
    return text
}

internal fun formatCatalogPct(cov: CatalogCoverage?): String {
    if (cov == null) return "0"
    if (cov.heard > 0 && cov.pct < 0.1) return "<0.1"
    return formatAnalyticsPct(cov.pct, maxDecimals = 2)
}

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

internal fun trimActivityPoints(points: List<ActivityPoint>, period: ActivityPeriod): List<ActivityPoint> {
    if (period != ActivityPeriod.Day || points.isEmpty()) return points
    val firstPlay = points.indexOfFirst { it.count > 0 }
    if (firstPlay <= 1) return points
    return points.drop(firstPlay - 1)
}

internal fun formatActivityLabel(label: String, period: ActivityPeriod): String = when (period) {
    ActivityPeriod.Day -> runCatching {
        val d = LocalDate.parse(label)
        "${d.monthValue}/${d.dayOfMonth}"
    }.getOrDefault(label.takeLast(5).replace('-', '/'))
    ActivityPeriod.Week -> label.substringAfter('-', label).removePrefix("W")
    ActivityPeriod.Month -> runCatching {
        val parts = label.split('-')
        if (parts.size == 2) {
            val month = parts[1].toInt()
            listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[month - 1]
        } else label
    }.getOrDefault(label)
    ActivityPeriod.Year -> label
}

internal fun chartAxisLabels(labels: List<String>, maxTicks: Int = 6): List<Pair<Int, String>> {
    if (labels.isEmpty()) return emptyList()
    if (labels.size <= maxTicks) return labels.mapIndexed { index, label -> index to label }
    val step = ((labels.size - 1).toFloat() / (maxTicks - 1)).coerceAtLeast(1f)
    return buildList {
        var i = 0f
        while (i < labels.size) {
            add(i.toInt() to labels[i.toInt()])
            i += step
        }
        if (last().first != labels.lastIndex) add(labels.lastIndex to labels.last())
    }
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
    var activityPeriod by remember { mutableStateOf(ActivityPeriod.Day) }
    var customFrom by remember { mutableStateOf<LocalDate?>(null) }
    var customTo by remember { mutableStateOf<LocalDate?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    val activeMemberId by ActiveProfileStore.activeMemberIdState.collectAsState()
    val profileRevision by ClientPrefsSync.profileChangeRevision.collectAsState()

    val effectivePreset = if (customFrom != null || customTo != null) DatePreset.Custom else DatePreset.AllTime
    val queryKey = analyticsRangeKey(effectivePreset, customFrom, customTo, activeMemberId)

    suspend fun loadAnalytics() {
        if (data == null) loading = true else refreshing = true
        val (from, to) = analyticsDateRange(effectivePreset, customFrom, customTo)
        val householdWide = activeMemberId.isNullOrBlank()
        try {
            data = repository.analytics(from, to, deviceId = null, householdWide = householdWide)
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

    LaunchedEffect(queryKey, activeMemberId, profileRevision) {
        loadAnalytics()
    }

    val onExportClick: () -> Unit = {
        scope.launch {
            exporting = true
            val (from, to) = analyticsDateRange(effectivePreset, customFrom, customTo)
            runCatching {
                val file = repository.exportAnalyticsCsv(
                    from,
                    to,
                    context.cacheDir,
                    deviceId = null,
                    householdWide = activeMemberId.isNullOrBlank(),
                )
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
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    Column(Modifier.fillMaxSize()) {
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
                val analytics = data
                val hasDeviceActivity = analytics?.deviceBreakdown?.any {
                    it.plays + it.downloads + it.connects > 0
                } == true
                val showEmpty = analytics != null && analytics.totalPlays == 0 && !hasDeviceActivity
                BockLazyColumn(
                    Modifier
                        .fillMaxSize()
                        .testTag(BockTestTags.ANALYTICS_BODY),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "toolbar-$queryKey") {
                        AnalyticsToolbar(
                            customFrom = customFrom,
                            customTo = customTo,
                            exporting = exporting,
                            filtered = customFrom != null || customTo != null,
                            onFrom = { showFromPicker = true },
                            onTo = { showToPicker = true },
                            onClear = {
                                customFrom = null
                                customTo = null
                            },
                            onExport = onExportClick,
                        )
                    }
                    if (showEmpty) {
                        item(key = "empty-$queryKey") {
                            AnalyticsEmptyState()
                        }
                    } else analytics?.let { a ->
                        item(key = "stats-$queryKey") { SummaryStatsGrid(a) }
                        item(key = "activity-$queryKey") {
                            ActivityChartCard(
                                data = a,
                                period = activityPeriod,
                                onPeriod = { activityPeriod = it },
                                trimDaySeries = customFrom == null && customTo == null,
                            )
                        }
                        item(key = "hour-dow-$queryKey") {
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AnalyticsCard(title = "Hour of Day") {
                                    HourOfDayChart(a.hourOfDay)
                                }
                                AnalyticsCard(title = "Day of Week") {
                                    DayOfWeekChart(a.dayOfWeek)
                                }
                            }
                        }
                        if (!a.heatmap.isNullOrEmpty()) {
                            item(key = "heatmap-$queryKey") { HeatmapCard(a.heatmap!!) }
                        }
                        item(key = "artists-$queryKey") { RankingCard("Top Artists", a.topArtists, BockGreen) }
                        item(key = "albums-$queryKey") { RankingCard("Top Albums", a.topAlbums, BockGold) }
                        item(key = "tracks-$queryKey") { RankingCard("Top Tracks", a.topTracks, Color(0xFF509BF5)) }
                        item(key = "devices-top-$queryKey") { RankingCard("Top Devices", a.topDevices, BockNavy) }
                        if (a.topGenres.isNotEmpty() || a.topDecades.isNotEmpty()) {
                            item(key = "genres-decades-$queryKey") {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (a.topGenres.isNotEmpty()) {
                                        AnalyticsCard("Top Genres") {
                                            GenreShareChart(a.topGenres)
                                        }
                                    }
                                    if (a.topDecades.isNotEmpty()) {
                                        AnalyticsCard("By Decade") {
                                            DecadeChart(a.topDecades)
                                        }
                                    }
                                }
                            }
                        }
                        if (a.playsPerDay.size >= 7) {
                            a.entityActivity?.artists?.takeIf { it.isNotEmpty() }?.let { series ->
                                item(key = "entity-artists-$queryKey") {
                                    EntityActivityCard("Artist Activity Over Time", series)
                                }
                            }
                            a.entityActivity?.albums?.takeIf { it.isNotEmpty() }?.let { series ->
                                item(key = "entity-albums-$queryKey") {
                                    EntityActivityCard("Album Activity Over Time", series)
                                }
                            }
                            a.entityActivity?.tracks?.takeIf { it.isNotEmpty() }?.let { series ->
                                item(key = "entity-tracks-$queryKey") {
                                    EntityActivityCard("Track Activity Over Time", series)
                                }
                            }
                            a.entityActivity?.devices?.takeIf { it.isNotEmpty() }?.let { series ->
                                item(key = "entity-devices-$queryKey") {
                                    EntityActivityCard("Device Activity Over Time", series)
                                }
                            }
                        }
                    }
                    if (analytics?.deviceBreakdown?.any { it.plays + it.downloads + it.connects > 0 } == true) {
                        item(key = "devices-$queryKey") {
                            DeviceBreakdownCard(analytics!!.deviceBreakdown)
                        }
                    }
                    item(key = "ignored-header") {
                        AnalyticsCard("Never Play Again") {
                            if (ignored.isEmpty()) {
                                Text(
                                    "No ignored tracks. Use the block button in Now Playing to never play a song again.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BockMuted,
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ignored.forEach { track ->
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
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsToolbar(
    customFrom: LocalDate?,
    customTo: LocalDate?,
    exporting: Boolean,
    filtered: Boolean,
    onFrom: () -> Unit,
    onTo: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateScroll = rememberScrollState()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = SpotifyElevated,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(dateScroll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Date Range",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = BockMuted,
            )
            OutlinedButton(onClick = onFrom, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(
                    customFrom?.format(displayDate) ?: "mm/dd/yyyy",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Text("to", style = MaterialTheme.typography.labelMedium, color = BockMuted)
            OutlinedButton(onClick = onTo, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(
                    customTo?.format(displayDate) ?: "mm/dd/yyyy",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (filtered) {
                TextButton(onClick = onClear) { Text("Clear") }
                Text(
                    "Filtered",
                    style = MaterialTheme.typography.labelMedium,
                    color = BockGold,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onExport,
                enabled = !exporting,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (exporting) "Exporting…" else "Export CSV", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AnalyticsEmptyState() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SpotifyElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "No device activity yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Play music on Alexa, Android, or iOS — or download offline on Android — to build analytics.",
                style = MaterialTheme.typography.bodyMedium,
                color = BockMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SummaryStatsGrid(data: AnalyticsResponse) {
    val streak = data.listeningStreak ?: ListeningStreak(data.currentStreak, data.longestStreak)
    val cov = data.catalogCoverage
    val rr = data.repeatRate
    val tiles = buildList {
        add(StatTile(Icons.Default.Headphones, Color(0x26509BF5), Color(0xFF509BF5), intFmt.format(data.totalPlays), null, "Total Plays"))
        add(StatTile(Icons.Default.LocalFireDepartment, Color(0x26E99D1A), BockGold, "${streak.current}", " day streak", "Current Streak"))
        add(StatTile(Icons.Default.EmojiEvents, Color(0x261DB954), BockGreen, "${streak.longest}", " days", "Longest Streak"))
        add(
            StatTile(
                Icons.Default.Album,
                Color(0x268D67AB),
                Color(0xFF8D67AB),
                formatCatalogPct(cov),
                "%",
                cov?.let { "Catalog Heard · ${intFmt.format(it.heard)}/${intFmt.format(it.total)}" } ?: "Catalog Heard",
            ),
        )
        add(StatTile(Icons.Default.Repeat, Color(0x261A9BA1), Color(0xFF1A9BA1), "${rr?.pct ?: 0}", "%", "Repeat Rate"))
        data.mostActiveDay?.let { mad ->
            add(
                StatTile(
                    Icons.Default.CalendarMonth,
                    Color(0x26E99D1A),
                    BockGold,
                    intFmt.format(mad.count),
                    null,
                    "Best Day · ${mad.date}",
                ),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { tile ->
            StatCard(
                icon = tile.icon,
                iconBackground = tile.iconBackground,
                iconTint = tile.iconTint,
                value = tile.value,
                valueSuffix = tile.valueSuffix,
                label = tile.label,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class StatTile(
    val icon: ImageVector,
    val iconBackground: Color,
    val iconTint: Color,
    val value: String,
    val valueSuffix: String?,
    val label: String,
)

@Composable
private fun StatCard(
    icon: ImageVector,
    iconBackground: Color,
    iconTint: Color,
    value: String,
    valueSuffix: String?,
    label: String,
    modifier: Modifier = Modifier,
) {
    val taggedModifier = modifier
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = SpotifyElevated,
        modifier = taggedModifier,
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = iconBackground,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        value,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (label == "Total Plays") {
                            Modifier.testTag(BockTestTags.ANALYTICS_TOTAL_PLAYS)
                        } else {
                            Modifier
                        },
                    )
                    valueSuffix?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = BockMuted,
                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                        )
                    }
                }
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = BockMuted,
                    letterSpacing = 0.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
    trimDaySeries: Boolean,
) {
    val rawPoints = when (period) {
        ActivityPeriod.Day -> data.activity?.day.orEmpty()
        ActivityPeriod.Week -> data.activity?.week.orEmpty()
        ActivityPeriod.Month -> data.activity?.month.orEmpty()
        ActivityPeriod.Year -> data.activity?.year.orEmpty()
    }
    val points = if (trimDaySeries && period == ActivityPeriod.Day) {
        trimActivityPoints(rawPoints, period)
    } else {
        rawPoints
    }
    AnalyticsCard("Activity Over Time") {
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
            val values = points.map { it.count }
            val labels = points.map { formatActivityLabel(it.label, period) }
            ActivityLineChart(values, labels, height = 180.dp)
        }
    }
}

@Composable
private fun ActivityLineChart(values: List<Int>, labels: List<String>, height: Dp) {
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val lineColor = Color(0xFF509BF5)
    val fillColor = lineColor.copy(alpha = 0.18f)
    val gridColor = Color.White.copy(alpha = 0.08f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val padTop = 8f
                val padBottom = 8f
                val chartH = size.height - padTop - padBottom
                val stepX = if (values.size <= 1) 0f else size.width / (values.size - 1)
                for (grid in 1..3) {
                    val y = padTop + chartH * grid / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (values.isEmpty()) return@Canvas
                val path = Path()
                val fillPath = Path()
                values.forEachIndexed { index, value ->
                    val x = if (values.size <= 1) size.width / 2f else index * stepX
                    val y = padTop + chartH - (value.toFloat() / max) * chartH
                    if (index == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, size.height)
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                val lastX = if (values.size <= 1) size.width / 2f else (values.size - 1) * stepX
                fillPath.lineTo(lastX, size.height)
                fillPath.close()
                drawPath(fillPath, fillColor)
                drawPath(path, lineColor, style = Stroke(width = 3f))
                values.forEachIndexed { index, value ->
                    if (value <= 0) return@forEachIndexed
                    val x = if (values.size <= 1) size.width / 2f else index * stepX
                    val y = padTop + chartH - (value.toFloat() / max) * chartH
                    drawCircle(lineColor, radius = 4f, center = Offset(x, y))
                }
            }
        }
        ChartAxisRow(labels)
    }
}

@Composable
private fun BarChart(
    values: List<Int>,
    labels: List<String>,
    color: Color,
    height: Dp,
) {
    if (values.isEmpty()) return
    val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val barCount = values.size
            val gap = size.width * 0.015f
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
            values.forEachIndexed { index, value ->
                val barHeight = (value.toFloat() / max) * size.height * 0.92f
                val x = index * (barWidth + gap)
                drawRoundRect(
                    color = if (value > 0) color else color.copy(alpha = 0.18f),
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
        }
        ChartAxisRow(labels)
    }
}

@Composable
private fun ChartAxisRow(labels: List<String>) {
    val ticks = chartAxisLabels(labels)
    if (ticks.isEmpty()) return
    Row(Modifier.fillMaxWidth()) {
        labels.indices.forEach { index ->
            val tick = ticks.find { it.first == index }
            Text(
                tick?.second.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = BockMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HourOfDayChart(hours: List<HourCount>) {
    val values = (0 until 24).map { h -> hours.find { it.hour == h }?.count ?: 0 }
    val labels = (0 until 24).map { hourLabel(it) }
    BarChart(values, labels, color = Color(0xFF509BF5), height = 160.dp)
}

@Composable
private fun DayOfWeekChart(days: List<DayCount>) {
    val order = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val values = order.map { d -> days.find { it.day.equals(d, true) }?.count ?: 0 }
    BarChart(values, order, color = BockGold, height = 160.dp)
}

@Composable
private fun DecadeChart(decades: List<DecadeRow>) {
    val values = decades.map { it.count }
    val labels = decades.map { it.decade ?: "—" }
    BarChart(values, labels, color = Color(0xFF8D67AB), height = 180.dp)
}

@Composable
private fun HeatmapCard(matrix: List<List<Int>>) {
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    val maxVal = matrix.flatMap { it }.maxOrNull()?.coerceAtLeast(1) ?: 1
    AnalyticsCard("Listening Heatmap — Hour × Day of Week") {
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
                                if (v > 0) Color(0xFF509BF5).copy(alpha = alpha) else SpotifyShortcutHover,
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
    if (active.isEmpty()) return
    val tableScroll = rememberScrollState()
    AnalyticsCard("Device Activity") {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(tableScroll),
        ) {
            Column(Modifier.widthIn(min = 520.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                ) {
                    DeviceTableHeader("Device", Modifier.weight(1.4f), TextAlign.Start)
                    DeviceTableHeader("Platform", Modifier.weight(0.9f))
                    DeviceTableHeader("Connects", Modifier.weight(0.7f))
                    DeviceTableHeader("Plays", Modifier.weight(0.7f))
                    DeviceTableHeader("Downloads", Modifier.weight(0.8f))
                    DeviceTableHeader("Last seen", Modifier.weight(0.9f))
                }
                active.forEach { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            d.name.ifBlank { d.deviceId.takeLast(8) },
                            modifier = Modifier.weight(1.4f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            d.platform.uppercase(),
                            modifier = Modifier.weight(0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            color = BockMuted,
                            textAlign = TextAlign.Center,
                        )
                        DeviceTableCell(intFmt.format(d.connects), Modifier.weight(0.7f))
                        DeviceTableCell(intFmt.format(d.plays), Modifier.weight(0.7f))
                        DeviceTableCell(intFmt.format(d.downloads), Modifier.weight(0.8f))
                        Text(
                            formatLastSeen(d.lastSeen),
                            modifier = Modifier.weight(0.9f),
                            style = MaterialTheme.typography.labelSmall,
                            color = BockMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceTableHeader(text: String, modifier: Modifier, align: TextAlign = TextAlign.Center) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = BockMuted,
        fontWeight = FontWeight.SemiBold,
        textAlign = align,
    )
}

@Composable
private fun DeviceTableCell(value: String, modifier: Modifier) {
    Text(
        value,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
    )
}

internal fun formatLastSeen(epochSec: Double?): String {
    if (epochSec == null || epochSec <= 0) return "—"
    val sec = ((System.currentTimeMillis() / 1000.0) - epochSec).toLong().coerceAtLeast(0)
    return when {
        sec < 60 -> "just now"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}

@Composable
private fun GenreShareChart(genres: List<CountRow>) {
    val items = genres.take(10)
    val total = items.sumOf { it.count }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { row ->
            val pct = row.count.toFloat() / total
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.displayName(),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        intFmt.format(row.count),
                        style = MaterialTheme.typography.labelMedium,
                        color = BockMuted,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SpotifyShortcutHover),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(pct)
                            .background(Color(0xFF8D67AB)),
                    )
                }
            }
        }
    }
}

private val entityChartColors = listOf(
    Color(0xFF4E91E6),
    Color(0xFFE6914E),
    Color(0xFF4EC74E),
    Color(0xFFE64E4E),
    Color(0xFFA44EE6),
)

@Composable
private fun EntityActivityCard(title: String, series: Map<String, Map<String, Int>>) {
    val allDays = remember(series) {
        series.values.flatMap { it.keys }.toSortedSet().toList()
    }
    if (allDays.isEmpty()) return
    AnalyticsCard(title) {
        val maxCount = series.values.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1) ?: 1
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            val padTop = 8f
            val padBottom = 8f
            val chartH = size.height - padTop - padBottom
            val stepX = if (allDays.size <= 1) 0f else size.width / (allDays.size - 1)
            series.entries.take(5).forEachIndexed { index, (_, dayCounts) ->
                val values = allDays.map { dayCounts[it] ?: 0 }
                val color = entityChartColors[index % entityChartColors.size]
                val path = Path()
                values.forEachIndexed { i, value ->
                    val x = i * stepX
                    val y = padTop + chartH - (value.toFloat() / maxCount) * chartH
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = color, style = Stroke(width = 3f))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            series.keys.take(5).forEachIndexed { index, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(entityChartColors[index % entityChartColors.size]),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = BockMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun IgnoredTrackRow(track: IgnoredTrack, onAllow: () -> Unit) {
    val title = track.track ?: track.path.substringAfterLast('/')
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val sub = buildString {
                track.artist?.let { append(it) }
                track.album?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (sub.isNotBlank()) {
                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = BockMuted)
            }
        }
        TextButton(onClick = onAllow) { Text("Allow again") }
    }
}
