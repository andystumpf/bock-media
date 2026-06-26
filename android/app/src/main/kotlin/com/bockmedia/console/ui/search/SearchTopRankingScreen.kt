package com.bockmedia.console.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.displayName
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.BockLazyColumn
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.rememberArtworkUrl
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.launch

enum class SearchRankingKind(val title: String) {
    Artists("Top Artists"),
    Albums("Top Albums"),
    Tracks("Top Tracks"),
    BestOf("Best Of…"),
}

private val displayDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun weekRange(weekOffset: Int): Pair<LocalDate, LocalDate> {
    val monday = LocalDate.now()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .plusWeeks(weekOffset.toLong())
    return monday to monday.plusDays(6)
}

private fun formatWeekRange(start: LocalDate, end: LocalDate): String =
    "${start.format(displayDateFmt)} - ${end.format(displayDateFmt)}"

private fun playCountLabel(count: Int): String =
    if (count == 1) "1 play" else "$count plays"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopRankingScreen(
    kind: SearchRankingKind,
    repository: BockMediaRepository,
    remoteOk: Boolean,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String, String?) -> Unit,
    onPlay: (PlayTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<CountRow>>(emptyList()) }
    var weekOffset by remember { mutableIntStateOf(0) }
    var year by remember { mutableIntStateOf(LocalDate.now().year) }

    val (weekStart, weekEnd) = remember(weekOffset) { weekRange(weekOffset) }
    val canGoForwardWeek = weekOffset < 0
    val canGoForwardYear = year < LocalDate.now().year

    LaunchedEffect(kind, weekOffset, year) {
        loading = true
        rows = emptyList()
        runCatching {
            val analytics = when (kind) {
                SearchRankingKind.BestOf -> repository.analytics(
                    from = "$year-01-01",
                    to = "$year-12-31",
                )
                else -> repository.analytics(
                    from = weekStart.toString(),
                    to = weekEnd.toString(),
                )
            }
            rows = when (kind) {
                SearchRankingKind.Artists -> analytics.topArtists
                SearchRankingKind.Albums -> analytics.topAlbums
                SearchRankingKind.Tracks -> analytics.topTracks
                SearchRankingKind.BestOf -> analytics.topTracks
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(kind.title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
        when (kind) {
            SearchRankingKind.BestOf -> PlexampYearHeader(
                year = year,
                onPrev = { year -= 1 },
                onNext = { if (canGoForwardYear) year += 1 },
                canGoForward = canGoForwardYear,
            )
            else -> PlexampWeekHeader(
                weekStart = weekStart,
                weekEnd = weekEnd,
                onPrev = { weekOffset -= 1 },
                onNext = { if (canGoForwardWeek) weekOffset += 1 },
                canGoForward = canGoForwardWeek,
            )
        }
        if (loading) {
            LoadingBox(Modifier.weight(1f))
        } else if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing played yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            BockLazyColumn(Modifier.weight(1f)) {
                items(rows.size, key = { index ->
                    val row = rows[index]
                    "${index}-${row.displayName()}-${row.artist.orEmpty()}"
                }) { index ->
                    val row = rows[index]
                    PlexampRankingRow(
                        rank = index + 1,
                        kind = kind,
                        row = row,
                        repository = repository,
                        onClick = {
                            when (kind) {
                                SearchRankingKind.Artists -> onOpenArtist(row.displayName())
                                SearchRankingKind.Albums -> onOpenAlbum(row.displayName(), row.artist)
                                SearchRankingKind.Tracks, SearchRankingKind.BestOf -> scope.launch {
                                    resolveTrackPath(repository, row)?.let { path ->
                                        onPlay(PlayTarget.Song(path, row.displayName()))
                                    } ?: row.artist?.let { artist ->
                                        onPlay(PlayTarget.Artist(artist))
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlexampWeekHeader(
    weekStart: LocalDate,
    weekEnd: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    canGoForward: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Weekly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                formatWeekRange(weekStart, weekEnd),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
        }
    }
}

@Composable
private fun PlexampYearHeader(
    year: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    canGoForward: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous year")
        }
        Text(
            year.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next year")
        }
    }
}

@Composable
private fun PlexampRankingRow(
    rank: Int,
    kind: SearchRankingKind,
    row: CountRow,
    repository: BockMediaRepository,
    onClick: () -> Unit,
) {
    val title = row.displayName()
    val (headline, subtitle, artistName, albumName) = when (kind) {
        SearchRankingKind.Artists -> Quad(title, null, title, null)
        SearchRankingKind.Albums -> Quad(row.artist ?: title, title, row.artist, title)
        SearchRankingKind.Tracks, SearchRankingKind.BestOf -> Quad(title, row.artist, row.artist, null)
    }
    val artShape: Shape = if (kind == SearchRankingKind.Artists) CircleShape else RoundedCornerShape(4.dp)
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = headline,
        artistName = artistName,
        albumName = albumName,
        albumArtist = row.artist,
        variantKey = "$kind-$rank-${row.artist.orEmpty()}-$title",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp),
        )
        BockArtwork(
            model = artUrl,
            title = headline,
            modifier = Modifier.size(52.dp),
            shape = artShape,
            fallbackFontSize = 18.sp,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                playCountLabel(row.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class Quad(
    val headline: String,
    val subtitle: String?,
    val artistName: String?,
    val albumName: String?,
)

private suspend fun resolveTrackPath(repository: BockMediaRepository, row: CountRow): String? {
    val title = row.displayName()
    return repository.songs(
        page = 1,
        search = title,
        artist = row.artist,
        limit = 5,
    ).items.firstOrNull()?.path
}
