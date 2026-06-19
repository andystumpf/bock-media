package com.bockmedia.console.ui.nowplaying

import android.os.Build
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.media.isLocalPhoneDevice
import com.bockmedia.console.media.toNowPlayingDevice
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.components.BockPullRefresh
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.ErrorText
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import com.bockmedia.console.ui.components.UpNextSheet
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

/** Puts the user-selected speaker first so Now Playing opens on the right device. */
fun orderDevicesForDisplay(
    items: List<NowPlayingDeviceItem>,
    alexaDevices: List<AlexaDevice>,
): List<NowPlayingDeviceItem> {
    PlaybackFocus.syncPendingFocus(items, alexaDevices)
    val sorted = sortedDevices(items)
    val focusId = PlaybackFocus.focusedDeviceId ?: return sorted
    val focus = sorted.find { it.deviceId == focusId } ?: return sorted
    return listOf(focus) + sorted.filter { it.deviceId != focusId }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NowPlayingScreen(
    repository: BockMediaRepository,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    playbackFocusGeneration: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localState by LocalPlaybackController.state.collectAsState()
    var items by remember { mutableStateOf<List<NowPlayingDeviceItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<StreamHistoryItem>>(emptyList()) }
    var alexaDevices by remember { mutableStateOf<List<AlexaDevice>>(emptyList()) }
    var controlsAvailable by remember { mutableStateOf(false) }
    var remoteOk by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var histPage by remember { mutableIntStateOf(1) }
    var histTotal by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var sleepDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var ignoreDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var favoritePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val volumes = remember { mutableStateMapOf<String, Int?>() }
    val shuffleOn = remember { mutableStateMapOf<String, Boolean>() }
    val volumeTimers = remember { mutableStateMapOf<String, kotlinx.coroutines.Job?>() }

    suspend fun refreshLive() {
        val np = repository.nowPlayingDevices()
        items = np.items.filter { !it.deviceId.startsWith("client-") }
        controlsAvailable = np.controlsAvailable
        remoteOk = alexaControlsAvailable(repository.alexaRemoteStatus())
        if (controlsAvailable && remoteOk && alexaDevices.isEmpty()) {
            runCatching { alexaDevices = repository.alexaRemoteDevices().devices }
        }
        PlaybackFocus.syncPendingFocus(np.items, alexaDevices)
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
        runCatching { favoritePaths = repository.favorites().map { it.path }.toSet() }
        error = liveError ?: historyError
        loading = false
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
        if (isLocalPhoneDevice(dev.deviceId)) {
            when (action) {
                "play", "pause" -> LocalPlaybackController.togglePlayPause(context)
                "next" -> LocalPlaybackController.skip(context, forward = true)
                "previous" -> LocalPlaybackController.skip(context, forward = false)
                "stop" -> LocalPlaybackController.stop(context)
                "shuffle_on" -> LocalPlaybackController.setShuffle(context, true)
                "shuffle_off" -> LocalPlaybackController.setShuffle(context, false)
            }
            return
        }
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
    LaunchedEffect(playbackFocusGeneration) {
        if (playbackFocusGeneration > 0) {
            runCatching { refreshLive() }
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

    if (showHistory) {
        StreamingHistorySheet(
            history = history,
            histTotal = histTotal,
            histPage = histPage,
            onPageChange = { histPage = it },
            onDismiss = { showHistory = false },
        )
    }

    when {
        loading && items.isEmpty() && history.isEmpty() && !localState.active -> LoadingBox()
        error != null && items.isEmpty() && history.isEmpty() && !localState.active -> ErrorText(error!!) { scope.launch { loadAll() } }
        else -> {
            val devices = remember(items, alexaDevices, playbackFocusGeneration, localState) {
                val remote = orderDevicesForDisplay(items, alexaDevices)
                localState.toNowPlayingDevice()?.let { local ->
                    listOf(local) + remote.filter { !isLocalPhoneDevice(it.deviceId) }
                } ?: remote
            }
            val pagerState = rememberPagerState(
                initialPage = PlaybackFocus.focusedIndex(devices).coerceIn(0, (devices.size - 1).coerceAtLeast(0)),
                pageCount = { devices.size.coerceAtLeast(1) },
            )
            val deviceIds = remember(devices) { devices.map { it.deviceId } }

            LaunchedEffect(deviceIds, playbackFocusGeneration) {
                if (devices.isEmpty()) return@LaunchedEffect
                if (pagerState.currentPage >= devices.size) {
                    pagerState.scrollToPage((devices.size - 1).coerceAtLeast(0))
                }
                val target = PlaybackFocus.focusedIndex(devices)
                if (target in devices.indices && target != pagerState.currentPage) {
                    pagerState.scrollToPage(target)
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                BockPullRefresh(
                    isRefreshing = refreshing,
                    onRefresh = { scope.launch { pullRefresh() } },
                    modifier = Modifier.fillMaxSize(),
                ) {
                if (devices.isEmpty()) {
                    SpotifyEmptyState(
                        onBack = onBack,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val pagerDotsVisible = devices.size > 1
                    VerticalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
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
                            pagerDotsVisible = pagerDotsVisible,
                            onBack = onBack,
                            onHistory = { showHistory = true },
                            onControl = { d, action -> scope.launch { runControl(d, action) } },
                            onSleep = { sleepDevice = it },
                            onIgnore = { ignoreDevice = it },
                            favoritePaths = favoritePaths,
                            onFavorite = { d ->
                                scope.launch {
                                    d.filepath?.let { path ->
                                        val liked = path in favoritePaths
                                        runCatching {
                                            if (liked) {
                                                repository.removeFavorite(path)
                                                snackbarHostState.showSnackbar("Removed from Liked")
                                            } else {
                                                repository.addFavorite(path, d.track, d.artist, d.album)
                                                snackbarHostState.showSnackbar("Added to Liked")
                                            }
                                            favoritePaths = repository.favorites().map { it.path }.toSet()
                                        }.onFailure {
                                            snackbarHostState.showSnackbar(
                                                if (liked) "Failed to remove from Liked" else "Failed to add to Liked",
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }

                    if (pagerDotsVisible) {
                        SpotifyDevicePagerHint(
                            currentPage = pagerState.currentPage,
                            totalPages = devices.size,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp),
                        )
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamingHistorySheet(
    history: List<StreamHistoryItem>,
    histTotal: Int,
    histPage: Int,
    onPageChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF121212),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Streaming history ($histTotal)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (history.isEmpty()) {
                Text(
                    if (histTotal > 0) "Could not load streaming history." else "No streaming history found.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BockLazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
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
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    item {
                        PaginationBar(histPage, ((histTotal + 24) / 25).coerceAtLeast(1)) { onPageChange(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpotifyEmptyState(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.statusBarsPadding(),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color.White.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Nothing is currently playing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ask Alexa to play a playlist, artist, or album",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SpotifyDevicePagerHint(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Color.Transparent),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(totalPages) { index ->
            Box(
                Modifier
                    .size(if (index == currentPage) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == currentPage) Color.White
                        else Color.White.copy(alpha = 0.35f),
                    ),
            )
        }
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
    pagerDotsVisible: Boolean = false,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onControl: (NowPlayingDeviceItem, String) -> Unit,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    onIgnore: (NowPlayingDeviceItem) -> Unit,
    favoritePaths: Set<String>,
    onFavorite: (NowPlayingDeviceItem) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val _t = tick
    val isLocal = isLocalPhoneDevice(dev.deviceId)
    val liveLocal by LocalPlaybackController.state.collectAsState()
    val prog = if (isLocal) {
        val durationMs = liveLocal.durationMs.coerceAtLeast(0)
        val positionMs = liveLocal.positionMs.coerceAtLeast(0)
        com.bockmedia.console.domain.model.NowPlayingProgress(
            if (durationMs > 0) positionMs.coerceAtMost(durationMs) else positionMs,
            durationMs,
        )
    } else {
        computeNowPlayingProgress(dev.timestamp, dev.duration_ms, dev.offset_ms, dev.paused)
    }
    val displayDev = if (isLocal) liveLocal.toNowPlayingDevice() ?: dev else dev
    val canControl = canControlDevice(displayDev, alexaDevices, controlsAvailable, remoteOk)
    LaunchedEffect(isLocal, liveLocal.shuffle, displayDev.deviceId) {
        if (isLocal) shuffleOn[displayDev.deviceId] = liveLocal.shuffle
    }
    val elapsedSec = prog.elapsedMs / 1000
    val durationSec = prog.durationMs / 1000
    var showMoreMenu by remember { mutableStateOf(false) }
    var showUpNext by remember { mutableStateOf(false) }
    var artUrl by remember(displayDev.filepath) { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val localArtFile = if (isLocal) liveLocal.current?.localFile else null
    LaunchedEffect(displayDev.filepath, localArtFile) {
        artUrl = repository.resolvePlaybackArtUrl(context, displayDev.filepath, localArtFile)
    }
    var year by remember(displayDev.filepath) { mutableStateOf(displayDev.year) }
    LaunchedEffect(displayDev.filepath, displayDev.year) {
        year = displayDev.year
        val fp = displayDev.filepath
        if (year == null && !fp.isNullOrBlank()) {
            year = repository.trackYear(fp)
        }
    }

    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < 640
    val artCorner = if (compact) 6.dp else 8.dp

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ArtBackdrop(artUrl = artUrl)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            SpotifyPlayerTopBar(
                deviceName = displayDev.deviceName,
                paused = displayDev.paused,
                isLocal = isLocal,
                onBack = onBack,
                onHistory = onHistory,
                onMore = { showMoreMenu = true },
            )

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val maxArt = minOf(
                    maxWidth - 28.dp,
                    maxHeight * if (compact) 0.78f else 0.84f,
                    maxWidth * 0.92f,
                )
                val artSize = maxArt.coerceAtLeast(180.dp)

                BockArtwork(
                    model = artUrl,
                    title = displayDev.track ?: "Now playing",
                    modifier = Modifier
                        .size(artSize)
                        .align(Alignment.TopCenter)
                        .padding(top = if (compact) 4.dp else 12.dp),
                    shape = RoundedCornerShape(artCorner),
                    fallbackFontSize = 48.sp,
                )

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(
                            if (pagerDotsVisible) Modifier.padding(bottom = 28.dp) else Modifier,
                        )
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.18f to Color.Black.copy(alpha = 0.45f),
                                    0.45f to Color.Black.copy(alpha = 0.82f),
                                    1f to Color.Black.copy(alpha = 0.95f),
                                ),
                            ),
                        )
                        .padding(top = 48.dp),
                ) {
                    SpotifyTrackInfoRow(
                        dev = displayDev,
                        year = year,
                        isLiked = displayDev.filepath?.let { it in favoritePaths } == true,
                        onFavorite = onFavorite,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )

                    if (displayDev.sleep != null || displayDev.paused) {
                        SpotifyStatusChips(
                            dev = displayDev,
                            onSleep = onSleep,
                            showPausedBadge = true,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }

                    SpotifyProgressBar(
                        fraction = prog.fraction,
                        elapsedSec = elapsedSec,
                        durationSec = durationSec,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp),
                    )

                    SpotifyTransportControls(
                        dev = displayDev,
                        shuffleOn = shuffleOn,
                        onControl = onControl,
                        onSleep = onSleep,
                        showShuffle = true,
                        showSleep = !isLocal,
                        enabled = canControl,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (canControl) {
                        SpotifyStopButton(
                            onClick = { onControl(displayDev, "stop") },
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(top = 4.dp, bottom = 4.dp),
                        )

                        val serial = if (isLocal) null else resolveSerial(displayDev, alexaDevices)
                        when {
                            isLocal -> LocalVolumeRow(
                                modifier = Modifier.padding(horizontal = 8.dp).padding(top = 4.dp, bottom = 8.dp),
                            )
                            serial != null -> SpotifyVolumeRow(
                                dev = displayDev,
                                serial = serial,
                                volume = volumes[dev.deviceId],
                                repository = repository,
                                volumes = volumes,
                                volumeTimers = volumeTimers,
                                scope = scope,
                                snackbarHostState = snackbarHostState,
                                modifier = Modifier.padding(horizontal = 8.dp).padding(top = 4.dp, bottom = 8.dp),
                            )
                        }
                    } else if (!isLocal) {
                        val reason = when {
                            !controlsAvailable -> "Playback controls aren't enabled on the server."
                            !remoteOk -> "Alexa session expired — re-login in Settings to control this device."
                            resolveSerial(displayDev, alexaDevices) == null ->
                                "Rename this device on the Devices page to match the Echo's Alexa name to enable controls."
                            else -> "Controls are unavailable for this device."
                        }
                        Text(
                            reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }

                    if (displayDev.upcoming.isNotEmpty()) {
                        SpotifyUpNext(
                            tracks = displayDev.upcoming,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 12.dp)
                                .clickable { showUpNext = true },
                        )
                    } else {
                        Spacer(Modifier.height(if (canControl) 4.dp else 16.dp))
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMoreMenu,
            onDismissRequest = { showMoreMenu = false },
        ) {
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
        if (showUpNext) {
            UpNextSheet(
                tracks = displayDev.upcoming,
                repository = repository,
                isLocalPlayback = isLocal,
                onPlayAtIndex = { upNextIndex ->
                    scope.launch {
                        LocalPlaybackController.seekToQueueIndex(
                            context,
                            liveLocal.index + 1 + upNextIndex,
                        )
                    }
                    showUpNext = false
                },
                onAlexaUnsupported = {
                    scope.launch {
                        snackbarHostState.showSnackbar("Skip from Up Next not supported on Alexa yet")
                    }
                },
                onDismiss = { showUpNext = false },
            )
        }
    }
}

@Composable
private fun ArtBackdrop(artUrl: String?) {
    Box(Modifier.fillMaxSize()) {
        SubcomposeAsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .scale(1.25f)
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(56.dp) else Modifier),
            contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) },
            error = { Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A))) },
            success = { SubcomposeAsyncImageContent() },
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    // Near-opaque at the very top/bottom so the inter-page seam (status-bar
                    // and nav-bar dead space) reads as dark and doesn't flash the blurred
                    // artwork as a bright colour band between device pages.
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.94f),
                        0.18f to Color.Black.copy(alpha = 0.5f),
                        0.5f to Color.Black.copy(alpha = 0.38f),
                        0.82f to Color.Black.copy(alpha = 0.6f),
                        1f to Color.Black.copy(alpha = 0.96f),
                    ),
                ),
        )
    }
}

@Composable
private fun SpotifyPlayerTopBar(
    deviceName: String?,
    paused: Boolean,
    isLocal: Boolean = false,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                deviceName ?: "Now playing",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (paused) "Paused" else if (isLocal) "Playing on this phone" else "Playing on Alexa",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
        IconButton(onClick = onHistory) {
            Icon(Icons.Default.History, contentDescription = "History", tint = Color.White)
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "More", tint = Color.White)
        }
    }
}


@Composable
private fun SpotifyTrackInfoRow(
    dev: NowPlayingDeviceItem,
    year: Int?,
    isLiked: Boolean,
    onFavorite: (NowPlayingDeviceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                dev.track ?: "—",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val artistLine = listOfNotNull(
                dev.artist?.takeIf { it.isNotBlank() },
                year?.takeIf { it > 0 }?.toString(),
            ).joinToString(" · ")
            if (artistLine.isNotBlank()) {
                Text(
                    artistLine,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            (dev.sourceLabel ?: dev.playlist)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (dev.filepath != null) {
            IconButton(onClick = { onFavorite(dev) }) {
                Icon(
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Remove from Liked" else "Add to Liked",
                    tint = if (isLiked) BockGreen else Color.White,
                )
            }
        }
    }
}

@Composable
private fun SpotifyStatusChips(
    dev: NowPlayingDeviceItem,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    showPausedBadge: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dev.paused && showPausedBadge) {
            AssistChip(
                onClick = {},
                label = { Text("Paused") },
                enabled = false,
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = Color.White.copy(alpha = 0.12f),
                    disabledLabelColor = Color.White.copy(alpha = 0.85f),
                ),
            )
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
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    labelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun SpotifyUpNext(
    tracks: List<UpcomingTrack>,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Up next",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.85f),
        )
        tracks.take(3).forEachIndexed { i, track ->
            Text(
                "${i + 2}. ${track.title ?: "—"}${track.artist?.let { " — $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatPlaybackTime(elapsedSec),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
            )
            if (durationSec > 0) {
                Text(
                    formatPlaybackTime(durationSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun SpotifyStopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.1f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Stop",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SpotifyTransportControls(
    dev: NowPlayingDeviceItem,
    shuffleOn: MutableMap<String, Boolean>,
    onControl: (NowPlayingDeviceItem, String) -> Unit,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    showShuffle: Boolean = true,
    showSleep: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shuffled = shuffleOn[dev.deviceId] == true
    val iconTint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showShuffle) {
            IconButton(
                onClick = {
                    val on = !shuffled
                    shuffleOn[dev.deviceId] = on
                    onControl(dev, if (on) "shuffle_on" else "shuffle_off")
                },
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (!enabled) iconTint
                        else if (shuffled) MaterialTheme.colorScheme.secondary
                        else Color.White.copy(alpha = 0.85f),
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
        IconButton(
            onClick = { onControl(dev, "previous") },
            enabled = enabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                tint = iconTint,
                modifier = Modifier.size(38.dp),
            )
        }
        Surface(
            onClick = { if (enabled) onControl(dev, if (dev.paused) "play" else "pause") },
            modifier = Modifier.size(if (LocalConfiguration.current.screenHeightDp < 640) 64.dp else 72.dp),
            shape = CircleShape,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            shadowElevation = if (enabled) 8.dp else 0.dp,
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
            enabled = enabled,
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                tint = iconTint,
                modifier = Modifier.size(38.dp),
            )
        }
        if (showSleep) {
            IconButton(
                onClick = { onSleep(dev) },
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = "Sleep timer",
                    tint = if (!enabled) iconTint
                        else if (dev.sleep != null) MaterialTheme.colorScheme.secondary
                        else Color.White.copy(alpha = 0.85f),
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
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
        Icon(Icons.Default.VolumeDown, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.65f))
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
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
        )
        Icon(Icons.Default.VolumeUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.65f))
    }
}

@Composable
private fun LocalVolumeRow(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audio = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }
    val maxVol = remember { audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableStateOf(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.VolumeDown, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.65f))
        Slider(
            value = level,
            onValueChange = { v ->
                level = v
                audio.setStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    v.toInt().coerceIn(0, maxVol),
                    0,
                )
            },
            valueRange = 0f..maxVol.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
            ),
        )
        Icon(Icons.Default.VolumeUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.65f))
    }
}
