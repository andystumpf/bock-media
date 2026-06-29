package com.bockmedia.console.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.api.dto.LyricsResponse
import com.bockmedia.console.ui.components.BockProgressIndicator
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

private fun activeLyricIndex(lines: List<com.bockmedia.console.data.api.dto.LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var lo = 0
    var hi = lines.lastIndex
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

private suspend fun LazyListState.scrollToCenteredItem(index: Int) {
    if (index < 0) return
    snapshotFlow { layoutInfo.viewportSize.height }
        .filter { it > 0 }
        .first()
    val layoutInfo = layoutInfo
    val itemHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 56
    val centerOffset = ((layoutInfo.viewportSize.height - itemHeight) / 2).coerceAtLeast(0)
    scrollToItem(index, scrollOffset = -centerOffset)
}

@Composable
fun LyricsTogglePill(
    showingLyrics: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLabel: String = "Cover",
    offLabel: String = "Lyrics",
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (showingLyrics) Icons.Default.Album else Icons.Default.Lyrics,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (showingLyrics) onLabel else offLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun LyricsPanel(
    lyrics: LyricsResponse?,
    loading: Boolean,
    error: String?,
    positionMs: Long,
    offsetMs: Int = 0,
    onOffsetChange: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> BockProgressIndicator(size = 32.dp)
            error != null -> Text(error, color = BockMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            lyrics == null -> Text("No lyrics available", color = BockMuted, textAlign = TextAlign.Center)
            lyrics.lines.isNotEmpty() -> SyncedLyricsList(
                lyrics = lyrics,
                positionMs = positionMs,
                offsetMs = offsetMs,
                onOffsetChange = onOffsetChange,
            )
            lyrics.plain.isNotBlank() -> PlainLyricsText(lyrics.plain)
            else -> Text("No lyrics available", color = BockMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SyncedLyricsList(
    lyrics: LyricsResponse,
    positionMs: Long,
    offsetMs: Int,
    onOffsetChange: ((Int) -> Unit)?,
) {
    val lines = lyrics.lines
    val adjustedMs = positionMs + offsetMs
    val activeIndex = activeLyricIndex(lines, adjustedMs)
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex < 0 || lines.isEmpty()) return@LaunchedEffect
        listState.scrollToCenteredItem(activeIndex)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(lines, key = { idx, line -> "$idx-${line.timeMs}-${line.text}" }) { index, line ->
                val isActive = index == activeIndex
                val isPast = activeIndex >= 0 && index < activeIndex
                Text(
                    line.text,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = if (isActive) 26.sp else 22.sp,
                        lineHeight = if (isActive) 32.sp else 28.sp,
                    ),
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isActive -> Color.White
                        isPast -> Color.White.copy(alpha = 0.45f)
                        else -> Color.White.copy(alpha = 0.28f)
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (onOffsetChange != null) {
            LyricsOffsetControls(
                offsetMs = offsetMs,
                onOffsetChange = onOffsetChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun LyricsOffsetControls(
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepMs = 500
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = { onOffsetChange(offsetMs - stepMs) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Lyrics earlier", tint = Color.White)
            }
            Text(
                when {
                    offsetMs == 0 -> "Sync"
                    offsetMs > 0 -> "+${offsetMs / 1000f}s"
                    else -> "${offsetMs / 1000f}s"
                },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(min = 36.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { onOffsetChange(offsetMs + stepMs) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Lyrics later", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PlainLyricsText(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(
                "Not synced — add a .lrc file for timed lyrics",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
