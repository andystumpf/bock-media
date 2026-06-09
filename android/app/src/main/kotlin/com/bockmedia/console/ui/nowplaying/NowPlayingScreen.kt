package com.bockmedia.console.ui.nowplaying

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.bockmedia.console.ui.components.BockLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

private fun controlErrorMessage(e: Throwable): String {
    if (e is HttpException) {
        val raw = e.response()?.errorBody()?.string().orEmpty()
        if (raw.contains("not_authenticated")) return "Alexa session expired — re-login in Settings"
    }
    return e.message?.takeIf { it.isNotBlank() } ?: "Control failed"
}

private fun sortedDevices(items: List<NowPlayingDeviceItem>): List<NowPlayingDeviceItem> =
    items.sortedWith(compareBy({ it.paused }, { it.deviceName ?: it.deviceId }))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NowPlayingDeviceItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<StreamHistoryItem>>(emptyList()) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var controlsAvailable by remember { mutableStateOf(false) }
    var remoteOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var histPage by remember { mutableIntStateOf(1) }
    var histTotal by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var sleepDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var ignoreDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var refreshing by remember { mutableStateOf(false) }
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
        var liveError: String? = null
        var historyError: String? = null
        runCatching { refreshLive() }.onFailure { liveError = it.message }
        runCatching { loadHistory() }.onFailure { historyError = it.message }
        error = liveError ?: historyError
        loading = false
        refreshing = false
    }

    suspend fun pullRefresh() {
        refreshing = true
        try {
            var liveError: String? = null
            var historyError: String? = null
            runCatching { refreshLive() }.onFailure { liveError = it.message }
            runCatching { loadHistory() }.onFailure { historyError = it.message }
            error = liveError ?: historyError
        } finally {
            refreshing = false
        }
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
    LaunchedEffect(histPage) {
        runCatching { loadHistory() }.onFailure {
            if (error == null) error = it.message
        }
    }
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
            val devices = remember(items) { sortedDevices(items) }
            val playerHeight = LocalConfiguration.current.screenHeightDp.dp * 0.78f
            val pagerState = rememberPagerState(pageCount = { devices.size.coerceAtLeast(1) })
            val deviceIds = remember(devices) { devices.map { it.deviceId } }

            LaunchedEffect(deviceIds) {
                if (devices.isNotEmpty() && pagerState.currentPage >= devices.size) {
                    pagerState.scrollToPage((devices.size - 1).coerceAtLeast(0))
                }
            }

            BockPullRefresh(
                isRefreshing = refreshing,
                onRefresh = { scope.launch { pullRefresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                BockLazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (devices.isEmpty()) {
                        item {
                            SpotifyEmptyState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = playerHeight),
                            )
                        }
                    } else {
                        item(key = "player") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(playerHeight),
                            ) {
                                VerticalPager(
                                    state = pagerState,
                                    modifier = Modifier.weight(1f),
                                ) { page ->
                                    SpotifyNowPlayingPage(
                                        dev = devices[page],
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
                                if (devices.size > 1) {
                                    SpotifyDevicePagerHint(
                                        currentPage = pagerState.currentPage,
                                        totalPages = devices.size,
                                        deviceName = devices.getOrNull(pagerState.currentPage)?.deviceName,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            "Streaming history ($histTotal)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (history.isEmpty()) {
                        item {
                            Text(
                                if (histTotal > 0) {
                                    "Could not load streaming history."
                                } else {
                                    "No streaming history found."
                                },
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
}

@Composable
private fun SpotifyEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("Nothing is currently playing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Ask Alexa to play a playlist, artist, or album",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SpotifyDevicePagerHint(
    currentPage: Int,
    totalPages: Int,
    deviceName: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(totalPages) { index ->
                Box(
                    Modifier
                        .size(if (index == currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                deviceName?.let { append(it).append(" · ") }
                append("${currentPage + 1} of $totalPages")
                append(" · swipe up/down")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpotifyNowPlayingPage(
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
) {
    @Suppress("UNUSED_VARIABLE") val _t = tick
    val prog = computeNowPlayingProgress(dev.timestamp, dev.duration_ms, dev.offset_ms, dev.paused)
    val canControl = canControlDevice(dev, alexaDevices, controlsAvailable, remoteOk)
    val elapsedSec = prog.elapsedMs / 1000
    val durationSec = prog.durationMs / 1000
    var showMoreMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        dev.deviceName?.let { name ->
            AssistChip(
                onClick = {},
                label = { Text(name) },
                enabled = false,
                leadingIcon = { Icon(Icons.Default.Speaker, null, Modifier.size(16.dp)) },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val artSize = minOf(maxWidth, maxHeight)
            var artUrl by remember(dev.filepath) { mutableStateOf<String?>(null) }
            LaunchedEffect(dev.filepath) { artUrl = repository.artworkUrl(dev.filepath) }
            SubcomposeAsyncImage(
                model = artUrl,
                contentDescription = dev.album ?: "Album art",
                modifier = Modifier
                    .size(artSize)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
                loading = {
                    SpotifyArtPlaceholder(Modifier.size(artSize))
                },
                error = {
                    SpotifyArtPlaceholder(Modifier.size(artSize))
                },
                success = { SubcomposeAsyncImageContent() },
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    dev.track ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                dev.artist?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                (dev.sourceLabel ?: dev.playlist)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (dev.filepath != null) {
                IconButton(onClick = { onFavorite(dev) }) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = "Favorite")
                }
            }
        }

        if (dev.paused || dev.sleep != null) {
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (dev.paused) {
                    AssistChip(onClick = {}, label = { Text("Paused") }, enabled = false)
                }
                dev.sleep?.let { sleep ->
                    val sleepLabel = when (sleep.type) {
                        "time" -> "Sleep ${sleep.remainingMin ?: "?"}m"
                        else -> "${sleep.remaining ?: "?"} songs left"
                    }
                    AssistChip(
                        onClick = { onSleep(dev) },
                        label = { Text(sleepLabel) },
                        leadingIcon = { Icon(Icons.Default.Bedtime, null, Modifier.size(16.dp)) },
                    )
                }
            }
        }

        SpotifyProgressBar(
            fraction = prog.fraction,
            elapsedSec = elapsedSec,
            durationSec = durationSec,
            modifier = Modifier.padding(top = 12.dp),
        )

        if (canControl) {
            SpotifyTransportControls(
                dev = dev,
                shuffleOn = shuffleOn,
                onControl = onControl,
                onSleep = onSleep,
                modifier = Modifier.padding(top = 8.dp),
            )

            val serial = resolveSerial(dev, alexaDevices)
            if (serial != null) {
                SpotifyVolumeRow(
                    dev = dev,
                    serial = serial,
                    volume = volumes[dev.deviceId],
                    repository = repository,
                    volumes = volumes,
                    volumeTimers = volumeTimers,
                    scope = scope,
                    snackbarHostState = snackbarHostState,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "More")
                }
                DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                    if (dev.filepath != null) {
                        DropdownMenuItem(
                            text = { Text("Never play again") },
                            onClick = {
                                showMoreMenu = false
                                onIgnore(dev)
                            },
                            leadingIcon = { Icon(Icons.Default.Block, null) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Stop playback") },
                        onClick = {
                            showMoreMenu = false
                            onControl(dev, "stop")
                        },
                        leadingIcon = { Icon(Icons.Default.Stop, null) },
                    )
                }
            }
        }

        if (dev.upcoming.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
            ) {
                Text("Up next", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                dev.upcoming.take(3).forEachIndexed { i, track ->
                    Text(
                        "${i + 2}. ${track.title ?: "—"}${track.artist?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotifyArtPlaceholder(modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        Icon(
            Icons.Default.Album,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpotifyProgressBar(
    fraction: Float,
    elapsedSec: Long,
    durationSec: Long,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                disabledThumbColor = Color.White,
                disabledActiveTrackColor = Color.White,
                disabledInactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatPlaybackTime(elapsedSec),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (durationSec > 0) {
                Text(
                    formatPlaybackTime(durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpotifyTransportControls(
    dev: NowPlayingDeviceItem,
    shuffleOn: MutableMap<String, Boolean>,
    onControl: (NowPlayingDeviceItem, String) -> Unit,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shuffled = shuffleOn[dev.deviceId] == true
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = {
                val on = !shuffled
                shuffleOn[dev.deviceId] = on
                onControl(dev, if (on) "shuffle_on" else "shuffle_off")
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = "Shuffle",
                tint = if (shuffled) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
        IconButton(
            onClick = { onControl(dev, "previous") },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
        }
        Surface(
            onClick = { onControl(dev, if (dev.paused) "play" else "pause") },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = Color.White,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (dev.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (dev.paused) "Play" else "Pause",
                    tint = Color.Black,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(
            onClick = { onControl(dev, "next") },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
        }
        IconButton(
            onClick = { onSleep(dev) },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Sleep timer",
                tint = if (dev.sleep != null) MaterialTheme.colorScheme.primary else Color.White,
            )
        }
    }
}

@Composable
private fun SpotifyVolumeRow(
    dev: NowPlayingDeviceItem,
    serial: String,
    volume: Int?,
    repository: BockMediaRepository,
    volumes: MutableMap<String, Int?>,
    volumeTimers: MutableMap<String, kotlinx.coroutines.Job?>,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.VolumeDown, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = (volume ?: 50).toFloat(),
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
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
        )
        Icon(Icons.Default.VolumeUp, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
