package com.bockmedia.console.ui.nowplaying

import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import org.chromium.net.CronetEngine
import java.util.concurrent.Executors
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.bockmedia.console.ui.components.BockProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private const val YT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

@Volatile private var sharedCronetEngine: CronetEngine? = null
private var cronetUnavailable = false
private val cronetExecutor by lazy { Executors.newFixedThreadPool(4) }

/** Cronet (GmsCore) streams over cellular like the OS network stack — OkHttp/Default
 * HTTP stall on these long streams over some carriers. Null if Cronet is unavailable. */
private fun cronetEngineOrNull(context: android.content.Context): CronetEngine? {
    if (cronetUnavailable) return null
    sharedCronetEngine?.let { return it }
    return synchronized(MusicVideoCronet) {
        sharedCronetEngine ?: runCatching {
            CronetEngine.Builder(context.applicationContext)
                .enableQuic(true)
                .enableHttp2(true)
                .build()
        }.getOrNull().also {
            if (it == null) {
                cronetUnavailable = true
                android.util.Log.w("BockMV", "Cronet unavailable — falling back to DefaultHttpDataSource")
            }
            sharedCronetEngine = it
        }
    }
}

private object MusicVideoCronet

/** Full-bleed backdrop + player area; ExoPlayer/WebView use zoom/cover inside. */
@Composable
fun MusicVideoViewport(
    modifier: Modifier = Modifier,
    backdropUrl: String?,
    content: @Composable BoxScope.(Modifier) -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val frameModifier = Modifier.fillMaxSize()
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                modifier = frameModifier,
                contentScale = ContentScale.Crop,
            )
        }
        content(frameModifier)
    }
}

/** Build a muted ExoPlayer for music-video streams (CDN direct or NAS proxy).
 * Uses ExoPlayer's native DefaultHttpDataSource (not OkHttp) — the OkHttp data
 * source stalls on these streams over cellular while curl/native fetch fine. */
fun buildMusicVideoExoPlayer(
    context: android.content.Context,
    httpClient: OkHttpClient,
    playUrl: String,
    playing: Boolean = false,
    cdnHttpClient: OkHttpClient? = null,
    authHeaders: Map<String, String> = emptyMap(),
): ExoPlayer {
    val directCdn = isMusicVideoDirectCdnUrl(playUrl)
    val requestHeaders = if (directCdn) {
        mapOf("Referer" to "https://www.youtube.com/")
    } else {
        authHeaders
    }
    val cronet = cronetEngineOrNull(context)
    val httpFactory: HttpDataSource.Factory = if (cronet != null) {
        CronetDataSource.Factory(cronet, cronetExecutor)
            .setUserAgent(YT_USER_AGENT)
            // NAS proxy holds the first byte up to ~25s while yt-dlp resolves;
            // Cronet's 8s default kills the request and freezes on a still frame.
            .setConnectionTimeoutMs(35_000)
            .setReadTimeoutMs(35_000)
            .apply { if (requestHeaders.isNotEmpty()) setDefaultRequestProperties(requestHeaders) }
    } else {
        DefaultHttpDataSource.Factory()
            .setUserAgent(YT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(35_000)
            .setReadTimeoutMs(35_000)
            .apply { if (requestHeaders.isNotEmpty()) setDefaultRequestProperties(requestHeaders) }
    }
    val dataSource = if (directCdn) httpFactory else DefaultDataSource.Factory(context, httpFactory)
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
    val loadControl = if (directCdn) {
        // Start playback after ~0.5s of media so cellular users see video fast,
        // then keep filling up to 50s to ride out slow/throttled CDN segments.
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 50_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    } else {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 6_000, 150, 300)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = playing
            setMediaItem(MediaItem.fromUri(playUrl))
            prepare()
        }
}

/** Muted server stream or YouTube CDN URL — Bock audio stays on the library stream. */
@Composable
fun MusicVideoPlayer(
    playUrl: String?,
    videoId: String,
    artUrl: String?,
    loading: Boolean,
    @Suppress("UNUSED_PARAMETER") error: String?,
    httpClient: OkHttpClient?,
    playing: Boolean = true,
    positionMs: Long = 0L,
    onPlaybackFailed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cdnClient = remember { com.bockmedia.console.BockMediaApp.get(context).buildCdnHttpClient() }
    val authHeaders = remember { com.bockmedia.console.BockMediaApp.get(context).musicVideoAuthHeaders() }
    val fallbackThumb = remember(videoId) { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    val backdropUrl = artUrl?.takeIf { it.isNotBlank() } ?: fallbackThumb
    var isRendered by remember(playUrl) { mutableStateOf(false) }
    // The proxy's first byte can 503 while the NAS resolves yt-dlp/Piped; rebuild the
    // player a few times before declaring failure instead of freezing on a still frame.
    var retryAttempt by remember(playUrl) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val canPlay = !playUrl.isNullOrBlank()

    Box(modifier.fillMaxSize().clipToBounds()) {
        MusicVideoViewport(backdropUrl = backdropUrl) { frameModifier ->
        // Keep the player mounted while the stream URL is stable. Gating it behind
        // `loading` unmounts/rebuilds the player on every state flap → constant
        // re-buffering (choppy "still image every few seconds"). The spinner is an
        // overlay instead, so a stable playUrl means one player, one buffer.
        if (canPlay) {
            key(playUrl, retryAttempt) {
                val exo = remember(playUrl, retryAttempt) {
                    buildMusicVideoExoPlayer(
                        context,
                        httpClient ?: cdnClient,
                        playUrl!!,
                        playing,
                        cdnHttpClient = cdnClient,
                        authHeaders = authHeaders,
                    )
                }
                // Muted decorative video: only mirror play/pause. Do NOT seek to the
                // audio position — on cellular the video can't keep up, so repeated
                // seeks re-buffer endlessly (a new still frame every few seconds).
                LaunchedEffect(playing) {
                    exo.playWhenReady = playing
                }
                DisposableEffect(exo) {
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            isRendered = true
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) isRendered = true
                        }

                        override fun onPlayerError(changedError: PlaybackException) {
                            android.util.Log.w(
                                "BockMV",
                                "video playback error (attempt ${retryAttempt + 1}): ${changedError.message}",
                            )
                            if (retryAttempt < 4) {
                                scope.launch {
                                    delay(3_000)
                                    retryAttempt++
                                }
                            } else {
                                onPlaybackFailed()
                            }
                        }
                    }
                    exo.addListener(listener)
                    onDispose {
                        exo.removeListener(listener)
                        exo.release()
                    }
                }
                AndroidView(
                    modifier = frameModifier
                        .alpha(if (isRendered) 1f else 0f),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = exo
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    update = { it.player = exo },
                )
            }
        }

        if (!isRendered && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BockProgressIndicator(size = 32.dp)
            }
        }
        }
    }
}

@Composable
fun NowPlayingOverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(50),
        color = if (selected) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (selected) 0.5f else 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                BockProgressIndicator(size = 18.dp)
            } else {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
fun VideoModeTogglePill(
    showingVideo: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NowPlayingOverlayIconButton(
        icon = if (showingVideo) Icons.Default.Album else Icons.Default.Videocam,
        contentDescription = when {
            loading -> "Finding video"
            showingVideo -> "Switch to cover"
            else -> "Switch to video"
        },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        loading = loading,
        selected = showingVideo,
    )
}
