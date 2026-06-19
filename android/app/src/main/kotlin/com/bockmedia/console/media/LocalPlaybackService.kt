package com.bockmedia.console.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
import kotlinx.coroutines.runBlocking

class LocalPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var titles: List<String> = emptyList()
    private var artists: List<String> = emptyList()
    private var albums: List<String> = emptyList()
    private var paths: List<String> = emptyList()
    private var lastReportedIndex = -1
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            player?.let { exo ->
                if (exo.isPlaying) syncState(exo)
            }
            progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
                artists = intent.getStringArrayListExtra(EXTRA_ARTISTS).orEmpty()
                albums = intent.getStringArrayListExtra(EXTRA_ALBUMS).orEmpty()
                paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
                lastReportedIndex = -1
                val start = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)
                val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                startPlayback(urls, start, shuffle)
            }
            ACTION_TOGGLE -> player?.let { if (it.isPlaying) it.pause() else it.play() }
            ACTION_NEXT -> player?.seekToNextMediaItem()
            ACTION_PREVIOUS -> player?.seekToPreviousMediaItem()
            ACTION_SET_SHUFFLE -> player?.let {
                it.shuffleModeEnabled = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                syncState(it)
            }
            ACTION_STOP -> {
                DeviceAnalyticsReporter.clearPlayback(this)
                stopProgressUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                player?.release()
                player = null
                mediaSession?.release()
                mediaSession = null
                LocalPlaybackController.update { LocalPlaybackState() }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startPlayback(urls: List<String>, startIndex: Int, shuffle: Boolean = false) {
        if (urls.isEmpty()) return
        val exo = player ?: buildPlayer().also { player = it }
        exo.shuffleModeEnabled = shuffle
        val items = urls.mapIndexed { index, url ->
            MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titles.getOrNull(index))
                        .setArtist(artists.getOrNull(index))
                        .build(),
                )
                .build()
        }
        exo.setMediaItems(items, startIndex.coerceAtMost(items.lastIndex), 0)
        exo.prepare()
        exo.play()
        reportPlayIfNeeded(startIndex.coerceAtMost(items.lastIndex))
        startProgressUpdates()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun reportPlayIfNeeded(index: Int) {
        if (index == lastReportedIndex || index !in paths.indices) return
        lastReportedIndex = index
        val track = com.bockmedia.console.domain.model.LocalTrack(
            path = paths[index],
            title = titles.getOrNull(index).orEmpty().ifBlank { "Unknown" },
            artist = artists.getOrNull(index),
            album = albums.getOrNull(index),
        )
        DeviceAnalyticsReporter.reportPlay(this, track)
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun buildPlayer(): ExoPlayer {
        val httpClient = runBlocking { BockMediaApp.get(this@LocalPlaybackService).buildAuthenticatedHttpClient() }
        val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .build()
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncState(exo)
                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncState(exo)
                reportPlayIfNeeded(exo.currentMediaItemIndex)
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncState(exo)
                startForeground(NOTIFICATION_ID, buildNotification())
                if (playbackState == Player.STATE_ENDED) {
                    stopProgressUpdates()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    LocalPlaybackController.update { LocalPlaybackState() }
                    stopSelf()
                }
            }
        })
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, exo).build()
        return exo
    }

    private fun syncState(exo: ExoPlayer) {
        val duration = exo.duration
        val durationMs = if (duration == C.TIME_UNSET || duration < 0) 0 else duration
        LocalPlaybackController.onServiceState(
            index = exo.currentMediaItemIndex,
            isPlaying = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = durationMs,
            shuffle = exo.shuffleModeEnabled,
        )
        val idx = exo.currentMediaItemIndex
        if (idx in paths.indices) {
            val track = com.bockmedia.console.domain.model.LocalTrack(
                path = paths[idx],
                title = titles.getOrNull(idx).orEmpty().ifBlank { "Unknown" },
                artist = artists.getOrNull(idx),
                album = albums.getOrNull(idx),
            )
            DeviceAnalyticsReporter.reportPlayback(
                this,
                track,
                playing = exo.isPlaying,
                offsetMs = exo.currentPosition.coerceAtLeast(0),
                durationMs = durationMs,
            )
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val idx = player?.currentMediaItemIndex ?: 0
        val title = titles.getOrNull(idx) ?: "Bock Media"
        val artist = artists.getOrNull(idx) ?: ""
        val exo = player
        val isPlaying = exo?.isPlaying == true
        val buffering = exo?.playbackState == Player.STATE_BUFFERING
        val duration = exo?.duration ?: 0L
        val position = exo?.currentPosition?.coerceAtLeast(0) ?: 0L
        val hasDuration = duration > 0 && duration != C.TIME_UNSET

        val prev = actionPendingIntent(ACTION_PREVIOUS, 1)
        val toggle = actionPendingIntent(
            if (isPlaying) ACTION_TOGGLE else ACTION_TOGGLE,
            2,
        )
        val next = actionPendingIntent(ACTION_NEXT, 3)

        val localTrack = LocalPlaybackController.state.value.tracks.getOrNull(idx)
        val artBitmap = localTrack?.localFile?.let { file ->
            PlaybackArtwork.embeddedArtUri(this, file)?.removePrefix("file://")?.let { path ->
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying || buffering)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prev)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                toggle,
            )
            .addAction(android.R.drawable.ic_media_next, "Next", next)

        artBitmap?.let { builder.setLargeIcon(it) }

        if (buffering) {
            builder.setProgress(0, 0, true)
        } else if (hasDuration) {
            builder.setProgress(duration.toInt(), position.toInt(), false)
        }

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }

        return builder.build()
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LocalPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Phone playback", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        stopProgressUpdates()
        player?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.bockmedia.console.local.PLAY"
        const val ACTION_TOGGLE = "com.bockmedia.console.local.TOGGLE"
        const val ACTION_NEXT = "com.bockmedia.console.local.NEXT"
        const val ACTION_PREVIOUS = "com.bockmedia.console.local.PREVIOUS"
        const val ACTION_STOP = "com.bockmedia.console.local.STOP"
        const val ACTION_SET_SHUFFLE = "com.bockmedia.console.local.SET_SHUFFLE"
        const val EXTRA_URLS = "urls"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_ALBUMS = "albums"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_START_INDEX = "startIndex"
        private const val CHANNEL_ID = "local_playback"
        private const val NOTIFICATION_ID = 42
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
