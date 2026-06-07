package com.bockmedia.console.ui.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(repository: BockMediaRepository) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NowPlayingDeviceItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<StreamHistoryItem>>(emptyList()) }
    var controls by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var histPage by remember { mutableIntStateOf(1) }
    var histTotal by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var sleepDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }

    suspend fun load() {
        runCatching {
            val np = repository.nowPlayingDevices()
            items = np.items
            controls = np.controlsAvailable
            val h = repository.streamHistory(histPage, 25)
            history = h.items
            histTotal = h.total
        }.onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(histPage) { load() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            runCatching {
                val np = repository.nowPlayingDevices()
                items = np.items
                controls = np.controlsAvailable
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    sleepDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { sleepDevice = null },
            title = { Text("Sleep timer — ${dev.deviceName}") },
            text = {
                Column {
                    listOf(15, 30, 45, 60).forEach { min ->
                        TextButton(onClick = {
                            scope.launch {
                                repository.setSleep(dev.deviceId, minutes = min)
                                sleepDevice = null
                                load()
                            }
                        }) { Text("$min minutes") }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            repository.setSleep(dev.deviceId, songs = 1)
                            sleepDevice = null
                            load()
                        }
                    }) { Text("After 1 song") }
                    TextButton(onClick = {
                        scope.launch {
                            repository.setSleep(dev.deviceId)
                            sleepDevice = null
                            load()
                        }
                    }) { Text("Cancel timer") }
                }
            },
            confirmButton = { TextButton(onClick = { sleepDevice = null }) { Text("Close") } },
        )
    }

    when {
        loading && items.isEmpty() && history.isEmpty() -> LoadingBox()
        error != null && items.isEmpty() -> ErrorText(error!!) { scope.launch { load() } }
        else -> LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
            item { Text("Now Playing", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp)) }
            if (items.isEmpty()) {
                item { Text("Nothing playing", modifier = Modifier.padding(16.dp)) }
            }
            items(items, key = { it.deviceId }) { dev ->
                @Suppress("UNUSED_VARIABLE") val _t = tick
                val prog = computeNowPlayingProgress(dev.timestamp, dev.duration_ms, dev.offset_ms, dev.paused)
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        var artUrl by remember(dev.filepath) { mutableStateOf<String?>(null) }
                        LaunchedEffect(dev.filepath) { artUrl = repository.artworkUrl(dev.filepath) }
                        AsyncImage(
                            model = artUrl,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(dev.track ?: "Unknown", style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(dev.artist, dev.deviceName).joinToString(" · "))
                            dev.sourceLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            LinearProgressIndicator(prog.fraction, Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        if (controls) {
                            Row {
                                IconButton(onClick = {
                                    scope.launch {
                                        repository.deviceControl(dev.deviceId, dev.deviceName ?: "", null, "previous")
                                        load()
                                    }
                                }) { Icon(Icons.Default.SkipPrevious, null) }
                                IconButton(onClick = {
                                    scope.launch {
                                        val action = if (dev.paused) "play" else "pause"
                                        repository.deviceControl(dev.deviceId, dev.deviceName ?: "", null, action)
                                        load()
                                    }
                                }) { Icon(if (dev.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null) }
                                IconButton(onClick = {
                                    scope.launch {
                                        repository.deviceControl(dev.deviceId, dev.deviceName ?: "", null, "next")
                                        load()
                                    }
                                }) { Icon(Icons.Default.SkipNext, null) }
                                IconButton(onClick = { sleepDevice = dev }) { Icon(Icons.Default.Bedtime, null) }
                                IconButton(onClick = {
                                    scope.launch {
                                        dev.filepath?.let { path ->
                                            repository.addFavorite(path, dev.track, dev.artist, dev.album)
                                        }
                                    }
                                }) { Icon(Icons.Default.Star, null) }
                                IconButton(onClick = {
                                    scope.launch {
                                        dev.filepath?.let { repository.addIgnored(it) }
                                        repository.deviceControl(dev.deviceId, dev.deviceName ?: "", null, "next")
                                        load()
                                    }
                                }) { Icon(Icons.Default.Block, null) }
                            }
                        }
                    }
                }
            }
            item {
                Text("Streaming history", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            }
            items(history) { h ->
                ListItem(
                    headlineContent = { Text(h.track ?: "—") },
                    supportingContent = { Text("${h.artist ?: ""} · ${h.device ?: ""}") },
                )
            }
            item {
                PaginationBar(histPage, ((histTotal + 24) / 25).coerceAtLeast(1)) { histPage = it }
            }
        }
    }
}
