package com.bockmedia.console.ui.nowplaying

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.bockmedia.console.ui.components.BockProgressIndicator
import okhttp3.OkHttpClient

/** Muted server stream or YouTube CDN URL — Bock audio stays on the library stream. */
@Composable
fun MusicVideoPlayer(
    playUrl: String?,
    videoId: String,
    loading: Boolean,
    error: String?,
    httpClient: OkHttpClient?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val thumbUrl = remember(videoId) { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
    var playerError by remember(playUrl) { mutableStateOf<String?>(null) }
    var isRendered by remember(playUrl) { mutableStateOf(false) }
    val displayError = error ?: playerError
    val canPlay = !playUrl.isNullOrBlank() && httpClient != null && displayError == null

    Box(modifier.fillMaxSize().clipToBounds()) {
        AsyncImage(
            model = thumbUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BockProgressIndicator(size = 32.dp)
                }
            }
            canPlay -> {
                key(playUrl) {
                    val exo = remember(playUrl, httpClient) {
                        val dataSource = DefaultDataSource.Factory(
                            context,
                            OkHttpDataSource.Factory(httpClient!!),
                        )
                        val loadControl = DefaultLoadControl.Builder()
                            .setBufferDurationsMs(
                                2_500,
                                8_000,
                                250,
                                500,
                            )
                            .build()
                        ExoPlayer.Builder(context)
                            .setLoadControl(loadControl)
                            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
                            .build()
                            .apply {
                                volume = 0f
                                repeatMode = Player.REPEAT_MODE_ONE
                                playWhenReady = true
                                setMediaItem(MediaItem.fromUri(playUrl!!))
                                prepare()
                            }
                    }
                    DisposableEffect(exo) {
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == Player.STATE_READY) {
                                    isRendered = true
                                }
                            }

                            override fun onPlayerError(changedError: PlaybackException) {
                                playerError = changedError.localizedMessage ?: "Playback failed"
                            }
                        }
                        exo.addListener(listener)
                        if (exo.playbackState == Player.STATE_READY) {
                            isRendered = true
                        }
                        onDispose {
                            exo.removeListener(listener)
                            exo.release()
                        }
                    }
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(if (isRendered) 1f else 0f),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                                player = exo
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        },
                        update = { it.player = exo },
                    )
                }
                if (!isRendered) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BockProgressIndicator(size = 32.dp)
                    }
                }
            }
            displayError != null -> {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        displayError,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(
                        onClick = {
                            val uri = Uri.parse("https://www.youtube.com/watch?v=$videoId")
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.youtube")
                            }
                            runCatching { context.startActivity(intent) }
                                .getOrElse { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        },
                    ) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open in YouTube")
                    }
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
