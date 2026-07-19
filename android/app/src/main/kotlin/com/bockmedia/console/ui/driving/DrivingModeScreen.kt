package com.bockmedia.console.ui.driving

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.bockmedia.console.ui.testing.BockTestTags
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.ui.components.ArtBackdrop
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.theme.BockGreen
import com.bockmedia.console.ui.theme.BockMuted
import kotlinx.coroutines.delay

@Composable
fun DrivingModeScreen(repository: BockMediaRepository, onOpenNowPlaying: () -> Unit) {
    val context = LocalContext.current
    val state by LocalPlaybackController.state.collectAsState()
    val track = state.current
    var artUrl by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        while (true) {
            delay(400)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val _tick = tick

    LaunchedEffect(track?.path, track?.localFile) {
        artUrl = repository.resolvePlaybackArtUrl(context, track?.path, track?.localFile)
    }

    val elapsedSec = state.positionMs.coerceAtLeast(0) / 1000
    val durationSec = state.durationMs.coerceAtLeast(0) / 1000
    val fraction = if (durationSec > 0) {
        (elapsedSec.toFloat() / durationSec).coerceIn(0f, 1f)
    } else {
        0f
    }
    val artSize = minOf(
        LocalConfiguration.current.screenWidthDp.dp - 48.dp,
        320.dp,
    )

    Box(Modifier.fillMaxSize().testTag(BockTestTags.DRIVING_BODY)) {
        ArtBackdrop(artUrl)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Driving",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.55f),
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.weight(0.12f))

            if (track != null) {
                BockArtwork(
                    model = artUrl,
                    title = track.title,
                    modifier = Modifier
                        .size(artSize)
                        .shadow(
                            elevation = 28.dp,
                            shape = RoundedCornerShape(14.dp),
                            ambientColor = Color.Black,
                            spotColor = Color.Black,
                        ),
                    shape = RoundedCornerShape(14.dp),
                    fallbackFontSize = 56.sp,
                )
            } else {
                DrivingEmptyArt(size = artSize)
            }

            Spacer(Modifier.height(28.dp))

            Text(
                track?.title ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (track != null) {
                Text(
                    listOfNotNull(
                        track.displayArtist,
                        track.album?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
                state.sourceLabel?.takeIf { it.isNotBlank() }?.let { source ->
                    Text(
                        source,
                        style = MaterialTheme.typography.labelLarge,
                        color = BockGreen,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                Text(
                    "Start something from Home or Library",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BockMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.weight(0.18f))

            if (track != null && durationSec > 0) {
                DrivingProgressBar(
                    fraction = fraction,
                    elapsedSec = elapsedSec,
                    durationSec = durationSec,
                )
                Spacer(Modifier.height(28.dp))
            } else {
                Spacer(Modifier.height(52.dp))
            }

            DrivingTransportRow(
                isPlaying = state.isPlaying,
                shuffleOn = state.shuffle,
                enabled = track != null,
                onPrevious = { LocalPlaybackController.skip(context, forward = false) },
                onTogglePlay = { LocalPlaybackController.togglePlayPause(context) },
                onNext = { LocalPlaybackController.skip(context, forward = true) },
                onShuffle = { LocalPlaybackController.setShuffle(context, !state.shuffle) },
            )

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = onOpenNowPlaying) {
                Text("Full player", color = Color.White.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun DrivingEmptyArt(size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.08f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Album,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                tint = Color.White.copy(alpha = 0.22f),
            )
        }
    }
}

@Composable
private fun DrivingProgressBar(
    fraction: Float,
    elapsedSec: Long,
    durationSec: Long,
) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.22f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatPlaybackTime(elapsedSec),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
            )
            Text(
                formatPlaybackTime(durationSec),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun DrivingTransportRow(
    isPlaying: Boolean,
    shuffleOn: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
) {
    val inactive = Color.White.copy(alpha = 0.28f)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrivingSecondaryButton(onClick = onShuffle, enabled = enabled, size = 60.dp) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                modifier = Modifier.size(28.dp),
                tint = if (enabled && shuffleOn) BockGreen else Color.White.copy(alpha = if (enabled) 0.85f else 0.35f),
            )
        }
        DrivingSecondaryButton(onClick = onPrevious, enabled = enabled, size = 76.dp) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(40.dp),
                tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
            )
        }
        Surface(
            onClick = onTogglePlay,
            enabled = enabled,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = if (enabled) Color.White else inactive,
            shadowElevation = if (enabled) 14.dp else 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = if (enabled) Color.Black else Color.Black.copy(alpha = 0.4f),
                    modifier = Modifier.size(52.dp),
                )
            }
        }
        DrivingSecondaryButton(onClick = onNext, enabled = enabled, size = 76.dp) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(40.dp),
                tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
            )
        }
    }
}

@Composable
private fun DrivingSecondaryButton(
    onClick: () -> Unit,
    enabled: Boolean,
    size: Dp,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (enabled) 0.14f else 0.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
