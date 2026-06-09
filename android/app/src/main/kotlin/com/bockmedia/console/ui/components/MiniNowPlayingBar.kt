package com.bockmedia.console.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.nowplaying.canControlDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MiniNowPlayingBar(
    repository: BockMediaRepository,
    remoteOk: Boolean,
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
            item = np.items.firstOrNull { !it.paused } ?: np.items.firstOrNull()
            if (controlsAvailable && alexaDevices.isEmpty()) {
                runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(5_000)
            refresh()
        }
    }

    val dev = item ?: return
    LaunchedEffect(dev.filepath) { artUrl = repository.artworkUrl(dev.filepath) }

    val canControl = canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenNowPlaying)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = artUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(10.dp))
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
                IconButton(onClick = {
                    scope.launch {
                        onControl(dev, if (dev.paused) "play" else "pause")
                        refresh()
                    }
                }) {
                    Icon(
                        if (dev.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (dev.paused) "Play" else "Pause",
                    )
                }
                IconButton(onClick = {
                    scope.launch {
                        onControl(dev, "next")
                        refresh()
                    }
                }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
        }
    }
}
