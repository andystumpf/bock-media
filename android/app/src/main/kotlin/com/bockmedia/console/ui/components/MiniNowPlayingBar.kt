package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.ui.nowplaying.canControlDevice
import com.bockmedia.console.ui.theme.MiniBarBottom
import com.bockmedia.console.ui.theme.MiniBarTop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MiniNowPlayingBar(
    repository: BockMediaRepository,
    remoteOk: Boolean,
    playbackFocusGeneration: Int = 0,
    onOpenNowPlaying: () -> Unit,
    onControl: suspend (NowPlayingDeviceItem, String) -> Unit,
) {
    var item by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var controlsAvailable by remember { mutableStateOf(false) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var artUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching {
            val np = repository.nowPlayingDevices()
            controlsAvailable = np.controlsAvailable
            if (controlsAvailable && alexaDevices.isEmpty()) {
                runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
            }
            PlaybackFocus.syncPendingFocus(np.items, alexaDevices)
            item = PlaybackFocus.resolveFocusedItem(np.items, alexaDevices)
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(5_000)
            refresh()
        }
    }
    LaunchedEffect(playbackFocusGeneration) {
        if (playbackFocusGeneration > 0) refresh()
    }

    val dev = item ?: return
    LaunchedEffect(dev.filepath) { artUrl = repository.artworkUrl(dev.filepath) }

    val canControl = canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)
    val barGradient = Brush.verticalGradient(
        colors = listOf(MiniBarTop, MiniBarBottom),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(barGradient),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dev.track ?: "Now playing",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(dev.artist, dev.deviceName).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canControl) {
                IconButton(
                    onClick = {
                        scope.launch {
                            onControl(dev, if (dev.paused) "play" else "pause")
                            refresh()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (dev.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (dev.paused) "Play" else "Pause",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            onControl(dev, "next")
                            refresh()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
