package com.bockmedia.console.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Lyrics
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
import com.bockmedia.console.ui.theme.BockGreen
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.launch

@Composable
fun LyricsTogglePill(
    showingLyrics: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
                if (showingLyrics) "Cover" else "Lyrics",
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
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> CircularProgressIndicator(color = BockGreen)
            error != null -> Text(error, color = BockMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            lyrics == null -> Text("No lyrics available", color = BockMuted, textAlign = TextAlign.Center)
            lyrics.lines.isNotEmpty() -> SyncedLyricsList(lyrics, positionMs)
            lyrics.plain.isNotBlank() -> PlainLyricsText(lyrics.plain)
            else -> Text("No lyrics available", color = BockMuted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SyncedLyricsList(lyrics: LyricsResponse, positionMs: Long) {
    val lines = lyrics.lines
    val activeIndex = remember(positionMs, lines) {
        lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeIndex) {
        if (lines.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(activeIndex.coerceIn(0, lines.lastIndex))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lines, key = { idx, line -> "$idx-${line.timeMs}-${line.text}" }) { index, line ->
            val isActive = index == activeIndex
            val isPast = index < activeIndex
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
}

@Composable
private fun PlainLyricsText(text: String) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
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
