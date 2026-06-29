package com.bockmedia.console.ui.nowplaying

import android.os.Build
import com.bockmedia.console.ui.theme.BockGreen
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clipToBounds
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
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.media.LocalPlaybackController
import com.bockmedia.console.media.isLocalPhoneDevice
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.media.toNowPlayingDevice
import com.bockmedia.console.ui.alexaControlsAvailable
import com.bockmedia.console.ui.components.BockArtwork
import com.bockmedia.console.ui.components.CompactStarRatingBar
import com.bockmedia.console.ui.components.ErrorText
import okhttp3.OkHttpClient
import com.bockmedia.console.ui.components.LoadingBox
import com.bockmedia.console.ui.components.PaginationBar
import com.bockmedia.console.ui.components.RatingKind
import com.bockmedia.console.ui.components.AddToRoomSheet
import com.bockmedia.console.ui.components.RoomRequestsSheet
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
    var error by remember { mutableStateOf<String?>(null) }
    var histPage by remember { mutableIntStateOf(1) }
    var histTotal by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var sleepDevice by remember { mutableStateOf<NowPlayingDeviceItem?>(null) }
    var showHistory by remember { mutableStateOf(false) }
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
            shuffleOn[dev.deviceId] = dev.shuffle
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
                mergeDevicesForDisplay(items, alexaDevices, localState)
            }
            val pagerState = rememberPagerState(
                initialPage = PlaybackFocus.focusedIndex(devices).coerceIn(0, (devices.size - 1).coerceAtLeast(0)),
                pageCount = { devices.size.coerceAtLeast(1) },
            )

            // Jump to the speaker the user just started — not on every poll (that blocks browsing others).
            LaunchedEffect(playbackFocusGeneration) {
                if (devices.isEmpty()) return@LaunchedEffect
                val target = PlaybackFocus.focusedIndex(devices).coerceIn(0, devices.lastIndex)
                pagerState.scrollToPage(target)
            }

            LaunchedEffect(devices.size) {
                if (devices.isEmpty()) return@LaunchedEffect
                if (pagerState.currentPage >= devices.size) {
                    pagerState.scrollToPage(devices.lastIndex)
                }
            }

            // Force a crisp page snap whenever a settle leaves a residual offset.
            // Without this the pager can rest a fraction off the boundary, letting the
            // next device page peek through as a colored band over the "Up next" list.
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.isScrollInProgress }
                    .collect { scrolling ->
                        if (!scrolling && pagerState.currentPageOffsetFraction != 0f) {
                            pagerState.scrollToPage(pagerState.currentPage)
                        }
                    }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
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
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds(),
                        beyondViewportPageCount = 0,
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
                            onRefresh = { scope.launch { runCatching { refreshLive() } } },
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

/** Remote Echo rows plus local phone when actively playing (matches iOS NowPlayingMerge). */
private fun mergeDevicesForDisplay(
    items: List<NowPlayingDeviceItem>,
    alexaDevices: List<AlexaDevice>,
    localState: com.bockmedia.console.media.LocalPlaybackState,
): List<NowPlayingDeviceItem> {
    val remote = orderDevicesForDisplay(items, alexaDevices)
    val local = localState.toNowPlayingDevice() ?: return remote
    return listOf(local) + remote.filter { !isLocalPhoneDevice(it.deviceId) }
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
    onRefresh: () -> Unit,
) {
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
    val context = LocalContext.current
    var showUpNext by remember { mutableStateOf(false) }
    var showRoomRequests by remember { mutableStateOf(false) }
    var showAddToRoom by remember { mutableStateOf(false) }
    var actingMemberId by remember { mutableStateOf(ActiveProfileStore.activeMemberId(context)) }
    var isParent by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val app = BockMediaApp.get(context)
    val showVideo by app.preferences.nowPlayingVideo.collectAsState(initial = false)
    var videoId by remember { mutableStateOf<String?>(null) }
    var videoLoading by remember { mutableStateOf(false) }
    var videoPlayUrl by remember { mutableStateOf<String?>(null) }
    var videoStreamError by remember { mutableStateOf<String?>(null) }
    var playbackHttpClient by remember { mutableStateOf<OkHttpClient?>(null) }
    var lyricsOffsetMs by remember { mutableIntStateOf(0) }
    var lyricsPositionMs by remember { mutableLongStateOf(0L) }
    @Suppress("UNUSED_VARIABLE") val _t = tick
    var lyrics by remember { mutableStateOf<com.bockmedia.console.data.api.dto.LyricsResponse?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    var artUrl by remember(displayDev.filepath) { mutableStateOf<String?>(null) }
    val localArtFile = if (isLocal) liveLocal.current?.localFile else null
    var lastSkipToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isLocal, liveLocal.error) {
        val err = liveLocal.error ?: return@LaunchedEffect
        if (isLocal && err != lastSkipToast) {
            lastSkipToast = err
            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(displayDev.filepath, localArtFile) {
        artUrl = repository.resolvePlaybackArtUrl(context, displayDev.filepath, localArtFile)
    }
    LaunchedEffect(Unit) {
        val me = ActiveProfileStore.activeMemberId(context)
        actingMemberId = me
        isParent = runCatching { repository.household().members }
            .getOrDefault(emptyList())
            .any { it.id == me && it.role == "parent" }
    }
    var year by remember(displayDev.filepath) { mutableStateOf(displayDev.year) }
    LaunchedEffect(displayDev.filepath, displayDev.year) {
        year = displayDev.year
        val fp = displayDev.filepath
        if (year == null && !fp.isNullOrBlank()) {
            year = repository.trackYear(fp)
        }
    }

    LaunchedEffect(displayDev.filepath) {
        showLyrics = false
        lyrics = null
        lyricsError = null
        lyricsOffsetMs = 0
    }

    LaunchedEffect(showVideo) {
        if (showVideo) showLyrics = false
    }

    val serverBaseUrl = remember(repository.baseUrlEpoch) { repository.peekBaseUrl() }

    LaunchedEffect(Unit) {
        playbackHttpClient = BockMediaApp.get(context).buildPlaybackHttpClient()
    }

    LaunchedEffect(displayDev.track, displayDev.artist, durationSec, serverBaseUrl) {
        videoId = null
        videoPlayUrl = null
        videoStreamError = null
        val title = displayDev.track?.trim().orEmpty()
        if (title.isEmpty()) {
            videoLoading = false
            return@LaunchedEffect
        }
        videoLoading = true
        val id = runCatching {
            repository.musicVideo(
                title = title,
                artist = displayDev.artist,
                durationSec = durationSec.takeIf { it > 0 }?.toInt(),
            )?.videoId
        }.getOrNull()?.takeIf { it.isNotBlank() }
        videoId = id
        videoLoading = false
        val base = serverBaseUrl?.takeIf { it.isNotBlank() }
        if (!id.isNullOrBlank() && base != null) {
            videoPlayUrl = repository.musicVideoProxyPlayUrl(base, id)
        }
    }

    LaunchedEffect(displayDev.filepath, displayDev.track, displayDev.artist, displayDev.album, durationSec) {
        val path = displayDev.filepath?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        lyricsLoading = true
        lyricsError = null
        val fetched = runCatching {
            repository.lyrics(
                path = path,
                durationSec = durationSec.takeIf { it > 0 }?.toInt(),
                title = displayDev.track,
                artist = displayDev.artist,
                album = displayDev.album,
            )
        }.getOrNull()
        lyrics = fetched
        lyricsLoading = false
        if (fetched == null || (fetched.lines.isEmpty() && fetched.plain.isBlank())) {
            if (showLyrics) lyricsError = "No lyrics found for this track"
        }
    }

    LaunchedEffect(showLyrics, isLocal, liveLocal.isPlaying, liveLocal.positionMs, displayDev.paused, dev.timestamp, dev.offset_ms, dev.duration_ms) {
        if (!showLyrics) return@LaunchedEffect
        var anchorPos = if (isLocal) liveLocal.positionMs else dev.offset_ms
        var anchorAt = System.currentTimeMillis()
        while (true) {
            if (isLocal) {
                val s = LocalPlaybackController.state.value
                if (s.positionMs != anchorPos) {
                    anchorPos = s.positionMs
                    anchorAt = System.currentTimeMillis()
                }
                val cap = s.durationMs.takeIf { it > 0 }
                lyricsPositionMs = if (s.isPlaying && !displayDev.paused) {
                    val live = anchorPos + (System.currentTimeMillis() - anchorAt)
                    if (cap != null) live.coerceAtMost(cap) else live
                } else {
                    s.positionMs
                }
            } else if (displayDev.paused) {
                lyricsPositionMs = dev.offset_ms
            } else {
                lyricsPositionMs = computeNowPlayingProgress(
                    dev.timestamp, dev.duration_ms, dev.offset_ms, false,
                ).elapsedMs
            }
            delay(50)
        }
    }

    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < 640
    val artCorner = if (compact) 6.dp else 8.dp

    Box(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        when {
            showVideo && !videoId.isNullOrBlank() ->
                MusicVideoPlayer(
                    playUrl = videoPlayUrl,
                    videoId = videoId!!,
                    loading = videoLoading,
                    error = videoStreamError,
                    httpClient = playbackHttpClient,
                    modifier = Modifier.fillMaxSize(),
                )
            else -> ArtBackdrop(artUrl = artUrl)
        }

        NowPlayingBottomFade(
            modifier = Modifier.align(Alignment.BottomCenter),
            deep = showVideo,
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            SpotifyPlayerTopBar(
                deviceName = displayDev.deviceName,
                isLocal = isLocal,
                playContext = (displayDev.sourceLabel ?: displayDev.playlist)?.takeIf { isLocal },
                onBack = onBack,
                onHistory = onHistory,
            )

            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (!showVideo) {
                    val maxArt = minOf(
                        maxWidth - 28.dp,
                        maxHeight * if (compact) 0.52f else 0.58f,
                        maxWidth * 0.92f,
                    )
                    val artSize = maxArt.coerceAtLeast(180.dp)

                    Box(
                        Modifier
                            .size(artSize)
                            .align(Alignment.TopCenter)
                            .padding(top = if (compact) 4.dp else 12.dp),
                    ) {
                        if (showLyrics) {
                            LyricsPanel(
                                lyrics = lyrics,
                                loading = lyricsLoading,
                                error = lyricsError,
                                positionMs = if (showLyrics) lyricsPositionMs else prog.elapsedMs,
                                offsetMs = lyricsOffsetMs,
                                onOffsetChange = { lyricsOffsetMs = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            BockArtwork(
                                model = artUrl,
                                title = displayDev.track ?: "Now playing",
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(artCorner),
                                fallbackFontSize = 48.sp,
                            )
                        }
                    }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(
                            if (pagerDotsVisible) Modifier.padding(bottom = 28.dp) else Modifier,
                        )
                        .padding(top = 48.dp),
                ) {
                    SpotifyTrackInfoRow(
                        dev = displayDev,
                        year = year,
                        repository = repository,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )

                    if (displayDev.sleep != null) {
                        SpotifyStatusChips(
                            dev = displayDev,
                            onSleep = onSleep,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }

                    SpotifyProgressBar(
                        fraction = prog.fraction,
                        elapsedSec = elapsedSec,
                        durationSec = durationSec,
                        seekable = isLocal && prog.durationMs > 0,
                        onSeek = { frac ->
                            LocalPlaybackController.seekTo(context, (frac * prog.durationMs).toLong())
                        },
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
                        val serial = if (isLocal) null else resolveSerial(displayDev, alexaDevices)
                        if (serial != null) {
                            SpotifyVolumeRow(
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

                    if (displayDev.upNext.isNotEmpty()) {
                        Column(
                            Modifier
                                .padding(horizontal = 24.dp)
                                .padding(bottom = 8.dp)
                                .clickable { showRoomRequests = true },
                        ) {
                            Text(
                                "Household requests",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            displayDev.upNext.take(4).forEach { req ->
                                val who = req.byMemberName?.takeIf { it.isNotBlank() } ?: "Someone"
                                val status = if (req.status == "queued") " · pending" else ""
                                Text(
                                    "${req.track ?: "Track"} · $who$status",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (remoteOk && !displayDev.filepath.isNullOrBlank() && !isLocal) {
                        TextButton(onClick = { showAddToRoom = true }) {
                            Text("Add to another room", color = Color.White.copy(alpha = 0.75f))
                        }
                    }

                    if (displayDev.upcoming.isNotEmpty()) {
                        SpotifyUpNext(
                            tracks = displayDev.upcoming,
                            modifier = Modifier
                                .clickable { showUpNext = true },
                        )
                    } else {
                        Spacer(Modifier.height(if (canControl) 4.dp else 16.dp))
                    }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (displayDev.filepath != null) {
                NowPlayingOverlayIconButton(
                    icon = if (showLyrics) Icons.Default.Album else Icons.Default.Lyrics,
                    contentDescription = if (showLyrics) "Show cover art" else "Show lyrics",
                    selected = showLyrics,
                    onClick = {
                        if (showLyrics) {
                            showLyrics = false
                        } else {
                            showLyrics = true
                            scope.launch {
                                app.preferences.setNowPlayingVideo(false)
                                ClientPrefsSync.schedulePush(context)
                            }
                        }
                    },
                )
            }
            if (videoId != null || videoLoading) {
                VideoModeTogglePill(
                    showingVideo = showVideo,
                    loading = videoLoading,
                    enabled = videoId != null,
                    onClick = {
                        if (videoId != null) {
                            val next = !showVideo
                            if (next) showLyrics = false
                            scope.launch {
                                app.preferences.setNowPlayingVideo(next)
                                ClientPrefsSync.schedulePush(context)
                            }
                        }
                    },
                )
            }
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
        if (showRoomRequests) {
            RoomRequestsSheet(
                deviceId = displayDev.deviceId,
                deviceName = displayDev.deviceName,
                requests = displayDev.upNext,
                repository = repository,
                actingMemberId = actingMemberId,
                isParent = isParent,
                onUpdated = {
                    onRefresh()
                    repository.roomQueue(displayDev.deviceId).queue
                        .filter { it.status == "queued" || it.status == "approved" }
                },
                onDismiss = { showRoomRequests = false },
            )
        }
        if (showAddToRoom && !displayDev.filepath.isNullOrBlank()) {
            AddToRoomSheet(
                repository = repository,
                path = displayDev.filepath!!,
                track = displayDev.track ?: "Track",
                artist = displayDev.artist,
                remoteOk = remoteOk,
                onDismiss = { showAddToRoom = false },
                onSuccess = { msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                    showAddToRoom = false
                },
                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            )
        }
    }
}

@Composable
private fun ArtBackdrop(artUrl: String?) {
    Box(Modifier.fillMaxSize().clipToBounds()) {
        SubcomposeAsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
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
    isLocal: Boolean = false,
    playContext: String? = null,
    onBack: () -> Unit,
    onHistory: () -> Unit,
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
                when {
                    isLocal && !playContext.isNullOrBlank() -> playContext
                    isLocal -> "This phone"
                    else -> "Alexa"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onHistory) {
            Icon(Icons.Default.History, contentDescription = "History", tint = Color.White)
        }
    }
}


@Composable
private fun SpotifyTrackInfoRow(
    dev: NowPlayingDeviceItem,
    year: Int?,
    repository: BockMediaRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val path = dev.filepath
    var stars by remember(path) { mutableIntStateOf(0) }
    var loadingRating by remember(path) { mutableStateOf(path != null) }

    LaunchedEffect(path) {
        if (path == null) {
            stars = 0
            loadingRating = false
            return@LaunchedEffect
        }
        loadingRating = true
        stars = runCatching { repository.ratingStars(RatingKind.Song, path) }.getOrDefault(0)
        loadingRating = false
    }

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
        if (path != null) {
            CompactStarRatingBar(
                stars = stars,
                loading = loadingRating,
                tint = Color.White,
                onStarsChange = { value ->
                    stars = value
                    scope.launch {
                        runCatching {
                            repository.setRating(
                                kind = RatingKind.Song,
                                id = path,
                                stars = value,
                                title = dev.track,
                                artist = dev.artist,
                                album = dev.album,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun SpotifyStatusChips(
    dev: NowPlayingDeviceItem,
    onSleep: (NowPlayingDeviceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
private fun NowPlayingBottomFade(modifier: Modifier = Modifier, deep: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(if (deep) 0.62f else 0.56f)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.22f to Color.Black.copy(alpha = if (deep) 0.35f else 0.22f),
                        0.48f to Color.Black.copy(alpha = if (deep) 0.62f else 0.48f),
                        0.72f to Color.Black.copy(alpha = 0.85f),
                        0.9f to Color.Black.copy(alpha = 0.96f),
                        1f to Color.Black,
                    ),
                ),
            ),
    )
}

@Composable
private fun SpotifyUpNext(
    tracks: List<UpcomingTrack>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.3f to Color.Black.copy(alpha = 0.5f),
                        0.65f to Color.Black.copy(alpha = 0.82f),
                        1f to Color.Black,
                    ),
                ),
            )
            .padding(top = 20.dp, bottom = 12.dp)
            .padding(horizontal = 24.dp),
    ) {
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
                modifier = Modifier.padding(top = if (i == 0) 4.dp else 2.dp),
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
    seekable: Boolean = false,
    onSeek: (Float) -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val sliderValue = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val shownElapsed = if (dragging && durationSec > 0) (sliderValue * durationSec).toLong() else elapsedSec
    val sliderColors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
        disabledThumbColor = Color.White,
        disabledActiveTrackColor = Color.White,
        disabledInactiveTrackColor = Color.White.copy(alpha = 0.28f),
    )
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { if (seekable) { dragging = true; dragFraction = it } },
            onValueChangeFinished = {
                if (seekable) { onSeek(dragFraction); dragging = false }
            },
            enabled = seekable,
            modifier = Modifier.fillMaxWidth(),
            colors = sliderColors,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatPlaybackTime(shownElapsed),
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
