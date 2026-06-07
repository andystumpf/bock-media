package com.bockmedia.console.ui.nowplaying

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

private sealed class NpDisplayEntry {
    data class Single(val item: NowPlayingDeviceItem) : NpDisplayEntry()
    data class Group(
        val name: String,
        val track: String,
        val artist: String?,
        val source: String?,
        val members: List<NowPlayingDeviceItem>,
    ) : NpDisplayEntry()
}

private fun resolveSerial(device: NowPlayingDeviceItem, alexaDevices: List<AlexaDevice>): String? {
    val name = device.deviceName?.lowercase() ?: return null
    return alexaDevices.firstOrNull { (it.name ?: "").lowercase() == name }?.serial
}

private fun canControlDevice(
    device: NowPlayingDeviceItem,
    alexaDevices: List<AlexaDevice>,
    controlsAvailable: Boolean,
    remoteOk: Boolean,
): Boolean {
    if (!controlsAvailable || !remoteOk) return false
    if (device.deviceId.startsWith("msp-")) return false
    if (device.deviceName.isNullOrBlank()) return false
    return resolveSerial(device, alexaDevices) != null
}

private fun groupNameFor(device: NowPlayingDeviceItem, groups: List<DeviceGroup>, alexaDevices: List<AlexaDevice>): String {
    val serial = resolveSerial(device, alexaDevices) ?: return ""
    return groups.firstOrNull { g -> g.members.any { it.serial == serial } }?.name ?: ""
}

private fun groupNowPlaying(
    items: List<NowPlayingDeviceItem>,
    groups: List<DeviceGroup>,
    alexaDevices: List<AlexaDevice>,
): List<NpDisplayEntry> {
    val out = mutableListOf<NpDisplayEntry>()
    val byKey = linkedMapOf<String, NpDisplayEntry.Group>()
    for (d in items) {
        val gname = groupNameFor(d, groups, alexaDevices)
        val track = d.track?.trim().orEmpty()
        val key = if (gname.isNotEmpty() && track.isNotEmpty()) "$gname\u0000$track" else ""
        if (key.isEmpty()) {
            out.add(NpDisplayEntry.Single(d))
            continue
        }
        val existing = byKey[key]
        if (existing != null) {
            byKey[key] = existing.copy(members = existing.members + d)
        } else {
            val entry = NpDisplayEntry.Group(
                name = gname,
                track = track,
                artist = d.artist,
                source = d.sourceLabel ?: d.playlist,
                members = listOf(d),
            )
            byKey[key] = entry
            out.add(entry)
        }
    }
    return out.map { e ->
        when (e) {
            is NpDisplayEntry.Single -> e
            is NpDisplayEntry.Group -> if (e.members.size < 2) NpDisplayEntry.Single(e.members.first()) else e
        }
    }
}

private fun controlErrorMessage(e: Throwable): String {
    if (e is HttpException) {
        val raw = e.response()?.errorBody()?.string().orEmpty()
        if (raw.contains("not_authenticated")) return "Alexa session expired — re-login in Settings"
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "Control failed"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NowPlayingDeviceItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<StreamHistoryItem>>(emptyList()) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var deviceGroups by remember { mutableStateOf<List<DeviceGroup>>(emptyList()) }
    var controlsAvailable by remember { mutableStateOf(false) }
    var remoteOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var histPage by remember { mutableIntStateOf(1) }
    var histTotal by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var sleepDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var ignoreDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    val volumes = remember { mutableStateMapOf<String, Int?>() }
    val shuffleOn = remember { mutableStateMapOf<String, Boolean>() }
    val volumeTimers = remember { mutableStateMapOf<String, kotlinx.coroutines.Job?>() }

    suspend fun refreshLive() {
        val np = repository.nowPlayingDevices()
        items = np.items
        controlsAvailable = np.controlsAvailable
        remoteOk = alexaControlsAvailable(repository.alexaRemoteStatus())
        if (controlsAvailable && remoteOk && alexaDevices.isEmpty()) {
            runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
            runCatching { deviceGroups = repository.deviceGroups().items }
        }
        for (dev in np.items) {
            if (!canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)) continue
            if (volumes.containsKey(dev.deviceId)) continue
            val serial = resolveSerial(dev, alexaDevices) ?: continue
            runCatching { volumes[dev.deviceId] = repository.getVolume(serial).volume }
        }
    }

    suspend fun loadHistory() {
        val h = repository.streamHistory(histPage, 25)
        history = h.items
        histTotal = h.total
    }

    suspend fun loadAll() {
        loading = true
        error = null
        runCatching {
            refreshLive()
            loadHistory()
        }.onFailure { error = it.message }
        loading = false
    }

    suspend fun runControl(dev: NowPlayingDeviceItem, action: String) {
        val serial = resolveSerial(dev, alexaDevices)
        if (serial == null) {
            snackbarHostState.showSnackbar(
                "Can't control \"${dev.deviceName}\" — rename it on Devices to match the Echo's Alexa name.",
            )
            return
        }
        runCatching {
            repository.deviceControl(dev.deviceId, dev.deviceName ?: "", serial, action)
            refreshLive()
        }.onFailure {
            snackbarHostState.showSnackbar(controlErrorMessage(it))
        }
    }

    LaunchedEffect(Unit) { loadAll() }
    LaunchedEffect(histPage) { runCatching { loadHistory() } }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            runCatching { refreshLive() }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    sleepDevice?.let { dev ->
        val armed = dev.sleep
        AlertDialog(
            onDismissRequest = { sleepDevice = null },
            title = { Text("Sleep timer — ${dev.deviceName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Playback stops at the end of the current song.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOf(15, 30, 45, 60).forEach { min ->
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    repository.setSleep(dev.deviceId, minutes = min)
                                    snackbarHostState.showSnackbar("Sleeping in $min min")
                                    refreshLive()
                                }.onFailure {
                                    snackbarHostState.showSnackbar(it.message ?: "Failed")
                                }
                                sleepDevice = null
                            }
                        }) { Text("$min minutes") }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                repository.setSleep(dev.deviceId, songs = 1)
                                snackbarHostState.showSnackbar("Stopping after this song")
                                refreshLive()
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Failed")
                            }
                            sleepDevice = null
                        }
                    }) { Text("After this song") }
                    TextButton(onClick = {
                        scope.launch {
                            runCatching {
                                repository.setSleep(dev.deviceId, songs = 3)
                                snackbarHostState.showSnackbar("Stopping after 3 songs")
                                refreshLive()
                            }.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "Failed")
                            }
                            sleepDevice = null
                        }
                    }) { Text("After 3 songs") }
                    if (armed != null) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    repository.setSleep(dev.deviceId)
                                    snackbarHostState.showSnackbar("Sleep timer cancelled")
                                    refreshLive()
                                }.onFailure {
                                    snackbarHostState.showSnackbar(it.message ?: "Failed")
                                }
                                sleepDevice = null
                            }
                        }) { Text("Cancel timer") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sleepDevice = null }) { Text("Close") } },
        )
    }

    ignoreDevice?.let { dev ->
        AlertDialog(
            onDismissRequest = { ignoreDevice = null },
            title = { Text("Never play again?") },
            text = { Text("\"${dev.track ?: "this song"}\" will be skipped in future playback.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val path = dev.filepath
                        if (path != null) {
                            runCatching {
                                repository.addIgnored(path)
                                snackbarHostState.showSnackbar("\"${dev.track ?: "Song"}\" won't play again")
                                if (canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)) {
                                    runControl(dev, "next")
                                }
                            }.onFailure {
                                snackbarHostState.showSnackbar("Failed to ignore track")
                            }
                        }
                        ignoreDevice = null
                    }
                }) { Text("Never again") }
            },
            dismissButton = { TextButton(onClick = { ignoreDevice = null }) { Text("Cancel") } },
        )
    }

    when {
        loading && items.isEmpty() && history.isEmpty() -> LoadingBox()
        error != null && items.isEmpty() && history.isEmpty() -> ErrorText(error!!) { scope.launch { loadAll() } }
        else -> {
            val entries = groupNowPlaying(items, deviceGroups, alexaDevices)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { scope.launch { loadAll() } }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Refresh")
                        }
                    }
                }

                if (items.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Nothing is currently playing", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Ask Alexa to play a playlist, artist, or album",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "${items.size} active ${if (items.size == 1) "device" else "devices"}",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    items(entries, key = {
                        when (it) {
                            is NpDisplayEntry.Single -> it.item.deviceId
                            is NpDisplayEntry.Group -> "group:${it.name}:${it.track}"
                        }
                    }) { entry ->
                        when (entry) {
                            is NpDisplayEntry.Group -> {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(8.dp))
                                            Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                            Spacer(Modifier.width(8.dp))
                                            Text("${entry.members.size} speakers", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(
                                            "${entry.track}${entry.artist?.let { " — $it" } ?: ""}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        entry.source?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        entry.members.forEach { member ->
                                            NowPlayingDeviceCard(
                                                dev = member,
                                                alexaDevices = alexaDevices,
                                                controlsAvailable = controlsAvailable,
                                                remoteOk = remoteOk,
                                                tick = tick,
                                                repository = repository,
                                                volumes = volumes,
                                                shuffleOn = shuffleOn,
                                                volumeTimers = volumeTimers,
                                                scope = scope,
                                                snackbarHostState = snackbarHostState,
                                                onControl = { d, action -> scope.launch { runControl(d, action) } },
                                                onSleep = { sleepDevice = it },
                                                onIgnore = { ignoreDevice = it },
                                                onFavorite = { d ->
                                                    scope.launch {
                                                        d.filepath?.let { path ->
                                                            runCatching {
                                                                repository.addFavorite(path, d.track, d.artist, d.album)
                                                                snackbarHostState.showSnackbar("Starred \"${d.track ?: "track"}\"")
                                                            }.onFailure {
                                                                snackbarHostState.showSnackbar("Failed to star track")
                                                            }
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                            is NpDisplayEntry.Single -> {
                                NowPlayingDeviceCard(
                                    dev = entry.item,
                                    alexaDevices = alexaDevices,
                                    controlsAvailable = controlsAvailable,
                                    remoteOk = remoteOk,
                                    tick = tick,
                                    repository = repository,
                                    volumes = volumes,
                                    shuffleOn = shuffleOn,
                                    volumeTimers = volumeTimers,
                                    scope = scope,
                                    snackbarHostState = snackbarHostState,
                                    onControl = { d, action -> scope.launch { runControl(d, action) } },
                                    onSleep = { sleepDevice = it },
                                    onIgnore = { ignoreDevice = it },
                                    onFavorite = { d ->
                                        scope.launch {
                                            d.filepath?.let { path ->
                                                runCatching {
                                                    repository.addFavorite(path, d.track, d.artist, d.album)
                                                    snackbarHostState.showSnackbar("Starred \"${d.track ?: "track"}\"")
                                                }.onFailure {
                                                    snackbarHostState.showSnackbar("Failed to star track")
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Streaming history ($histTotal)", style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (history.isEmpty()) {
                    item {
                        Text(
                            "No streaming history found.",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(history) { h ->
                    ListItem(
                        headlineContent = {
                            Text(h.track ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    h.artist?.takeIf { it.isNotBlank() },
                                    h.sourceLabel ?: h.playlist?.takeIf { it.isNotBlank() },
                                    h.device?.takeIf { it.isNotBlank() },
                                    h.date ?: h.timestamp,
                                ).joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
                item {
                    PaginationBar(histPage, ((histTotal + 24) / 25).coerceAtLeast(1)) { histPage = it }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingDeviceCard(
    dev: NowPlayingDeviceItem,
    alexaDevices: List<AlexaDevice>,
    controlsAvailable: Boolean,
    remoteOk: Boolean,
    tick: Int,
    repository: BockMediaRepository,
    volumes: MutableMap<String, Int?>,
    shuffleOn: MutableMap<String, Boolean>,
    volumeTimers: MutableMap<String, kotlinx.coroutines.Job?>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onControl: (NowPlayingDeviceItem, String) -> Unit,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    onIgnore: (NowPlayingDeviceItem) -> Unit,
    onFavorite: (NowPlayingDeviceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE") val _t = tick
    val prog = computeNowPlayingProgress(dev.timestamp, dev.duration_ms, dev.offset_ms, dev.paused)
    val canControl = canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)
    val elapsedSec = prog.elapsedMs / 1000
    val durationSec = prog.durationMs / 1000
    val timeLabel = if (durationSec > 0) {
        "${formatPlaybackTime(elapsedSec)} / ${formatPlaybackTime(durationSec)}"
    } else {
        formatPlaybackTime(elapsedSec)
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                var artUrl by remember(dev.filepath) { mutableStateOf<String?>(null) }
                LaunchedEffect(dev.filepath) { artUrl = repository.artworkUrl(dev.filepath) }
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dev.track ?: "—",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (dev.paused) {
                            Spacer(Modifier.width(6.dp))
                            AssistChip(onClick = {}, label = { Text("Paused") }, enabled = false)
                        }
                        dev.sleep?.let { sleep ->
                            Spacer(Modifier.width(6.dp))
                            val sleepLabel = when (sleep.type) {
                                "time" -> "${sleep.remainingMin ?: "?"}m"
                                else -> "${sleep.remaining ?: "?"} left"
                            }
                            AssistChip(
                                onClick = { onSleep(dev) },
                                label = { Text(sleepLabel) },
                                leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                    dev.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    dev.album?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    (dev.sourceLabel ?: dev.playlist)?.let {
                        Text("♪ $it", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "Device: ${dev.deviceName ?: dev.deviceId.takeLast(8)} · $timeLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (prog.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { prog.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            if (dev.upcoming.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Up next", style = MaterialTheme.typography.labelLarge)
                dev.upcoming.forEachIndexed { i, track ->
                    Text(
                        "${i + 2}. ${track.title ?: "—"}${track.artist?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (canControl) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = { onControl(dev, "previous") }) {
                        Icon(Icons.Default.SkipPrevious, "Previous")
                    }
                    IconButton(onClick = { onControl(dev, "play") }) {
                        Icon(Icons.Default.PlayArrow, "Play")
                    }
                    IconButton(onClick = { onControl(dev, "pause") }) {
                        Icon(Icons.Default.Pause, "Pause")
                    }
                    IconButton(onClick = { onControl(dev, "next") }) {
                        Icon(Icons.Default.SkipNext, "Next")
                    }
                    val shuffled = shuffleOn[dev.deviceId] == true
                    IconButton(onClick = {
                        val on = !shuffled
                        shuffleOn[dev.deviceId] = on
                        onControl(dev, if (on) "shuffle_on" else "shuffle_off")
                    }) {
                        Icon(
                            Icons.Default.Shuffle,
                            "Shuffle",
                            tint = if (shuffled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { onSleep(dev) }) {
                        Icon(Icons.Default.Bedtime, "Sleep timer")
                    }
                    if (dev.filepath != null) {
                        IconButton(onClick = { onFavorite(dev) }) {
                            Icon(Icons.Default.Star, "Favorite")
                        }
                        IconButton(onClick = { onIgnore(dev) }) {
                            Icon(Icons.Default.Block, "Never again")
                        }
                    }
                    IconButton(onClick = { onControl(dev, "stop") }) {
                        Icon(Icons.Default.Stop, "Stop")
                    }
                }

                val serial = resolveSerial(dev, alexaDevices)
                if (serial != null) {
                    val vol = volumes[dev.deviceId]
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.VolumeDown, null, Modifier.size(20.dp))
                        Slider(
                            value = (vol ?: 50).toFloat(),
                            onValueChange = { v ->
                                val intVol = v.toInt().coerceIn(0, 100)
                                volumes[dev.deviceId] = intVol
                                volumeTimers[dev.deviceId]?.cancel()
                                volumeTimers[dev.deviceId] = scope.launch {
                                    delay(350)
                                    runCatching {
                                        repository.setVolume(serial, dev.deviceName ?: "", intVol)
                                    }.onFailure {
                                        snackbarHostState.showSnackbar("Volume failed")
                                    }
                                }
                            },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(if (vol == null) "—" else vol.toString(), style = MaterialTheme.typography.labelSmall)
                        Icon(Icons.Default.VolumeUp, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
