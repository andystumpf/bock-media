package com.bockmedia.console.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.media.NowPlayingPollService
import com.bockmedia.console.media.toNowPlayingDevice
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localState by LocalPlaybackController.state.collectAsState()
    var item by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var controlsAvailable by remember { mutableStateOf(false) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var artUrl by remember { mutableStateOf<String?>(null) }

    val localItem = remember(localState) { localState.toNowPlayingDevice() }

    DisposableEffect(repository) {
        NowPlayingPollService.configure(repository)
        NowPlayingPollService.addSubscriber()
        onDispose { NowPlayingPollService.removeSubscriber() }
    }
    val polledItems by NowPlayingPollService.items.collectAsState()
    val polledControls by NowPlayingPollService.controlsAvailable.collectAsState()
    val polledAlexa by NowPlayingPollService.alexaDevices.collectAsState()

    val refreshRemote: suspend () -> Unit = {
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

    LaunchedEffect(polledItems, polledControls, polledAlexa, playbackFocusGeneration) {
        if (polledItems.isNotEmpty()) {
            controlsAvailable = polledControls
            if (polledAlexa.isNotEmpty()) alexaDevices = polledAlexa
            PlaybackFocus.syncPendingFocus(polledItems, alexaDevices)
            item = PlaybackFocus.resolveFocusedItem(polledItems, alexaDevices)
        } else if (playbackFocusGeneration > 0) {
            refreshRemote()
        }
    }

    LaunchedEffect(Unit) { refreshRemote() }

    val dev = localItem ?: item ?: return
    val localArtFile = localState.current?.localFile
    LaunchedEffect(dev.filepath, localArtFile) {
        artUrl = repository.resolvePlaybackArtUrl(context, dev.filepath, localArtFile)
    }

    val canControl = if (localItem != null) {
        true
    } else {
        canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)
    }

    val barGradient = Brush.verticalGradient(colors = listOf(MiniBarTop, MiniBarBottom))

    Box(modifier.fillMaxWidth().background(barGradient)) {
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
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dev.track ?: "Now playing",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = if (localItem != null) {
                    listOfNotNull(
                        dev.artist?.takeIf { it.isNotBlank() },
                        dev.sourceLabel ?: dev.playlist,
                    ).joinToString(" · ")
                } else {
                    listOfNotNull(dev.artist, dev.deviceName).joinToString(" · ")
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canControl) {
                if (localItem != null) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                LocalPlaybackController.setShuffle(context, !localState.shuffle)
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (localState.shuffle) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            if (localItem != null) {
                                LocalPlaybackController.togglePlayPause(context)
                            } else {
                                onControl(dev, if (dev.paused) "play" else "pause")
                                refreshRemote()
                            }
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
                            if (localItem != null) {
                                LocalPlaybackController.skip(context, forward = true)
                            } else {
                                onControl(dev, "next")
                                refreshRemote()
                            }
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
                IconButton(
                    onClick = {
                        scope.launch {
                            if (localItem != null) {
                                LocalPlaybackController.stop(context)
                            } else {
                                onControl(dev, "stop")
                            }
                            refreshRemote()
                        }
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
