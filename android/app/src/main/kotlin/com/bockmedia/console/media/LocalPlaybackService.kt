package com.bockmedia.console.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes as AndroidAudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.AppForegroundState
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
import com.bockmedia.console.domain.model.PlayTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class LocalPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var titles: List<String> = emptyList()
    private var artists: List<String> = emptyList()
    private var albums: List<String> = emptyList()
    private var paths: List<String> = emptyList()
    private var durationsMs: List<Long> = emptyList()
    private var urls: List<String> = emptyList()
    private var activeTargetKind: String? = null
    private var activeTargetPath: String? = null
    private var activeTargetArtist: String? = null
    private var activeTargetAlbum: String? = null
    private var activeTargetPlaylistId: String? = null
    private var lastReportedIndex = -1
    private var lastMetadataIndex = -1
    private var lastNotificationAtMs = 0L
    private var inForeground = false
    private var foregroundPromotionBlocked = false
    private var crossfadeMs: Long = 0
    private var crossfading = false
    private var incomingPlayer: ExoPlayer? = null
    private var crossfadeStartedAtMs = 0L
    private var crossfadeDurationMs = 0L
    private var crossfadeTargetIndex = -1
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var consecutivePlayErrors = 0
    private var retryErrorIndex = -1
    private var consecutiveSkips = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var playbackHttpClient: OkHttpClient? = null
    @Volatile private var notificationArtPath: String? = null
    @Volatile private var notificationArtBitmap: android.graphics.Bitmap? = null
    private var duckedForFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // ExoPlayer must be touched on the main thread; focus callbacks arrive on binder threads
        // (common when connecting/disconnecting Bluetooth in the car).
        mainHandler.post { handleAudioFocusChange(change) }
    }

    private fun handleAudioFocusChange(change: Int) {
        Log.i(TAG, "audioFocus change=$change crossfading=$crossfading playing=${leadPlayer()?.isPlaying}")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                duckedForFocus = false
                pauseAllPlayers()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = leadPlayer()?.isPlaying == true
                duckedForFocus = false
                pauseAllPlayers()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckedForFocus = true
                player?.volume = 0.2f
                incomingPlayer?.volume = if (crossfading) 0.2f else 0f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (duckedForFocus) {
                    duckedForFocus = false
                    player?.volume = 1f
                    if (crossfading) incomingPlayer?.volume = incomingPlayer?.volume?.coerceAtLeast(0f) ?: 0f
                } else if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    playAllPlayers()
                }
            }
        }
    }
    private val crossfadeHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val exo = leadPlayer() ?: return
            checkCrossfadeTrigger(exo)
            if (exo.isPlaying || crossfading) syncState(exo)
            val interval = if (crossfading || crossfadeMs > 0) 100L else PROGRESS_INTERVAL_MS
            progressHandler.postDelayed(this, interval)
        }
    }
    private val crossfadeVolumeRunnable = object : Runnable {
        override fun run() {
            if (!crossfading) return
            val outgoing = player ?: return
            val incoming = incomingPlayer ?: return
            val elapsed = System.currentTimeMillis() - crossfadeStartedAtMs
            val progress = (elapsed.toFloat() / crossfadeDurationMs).coerceIn(0f, 1f)
            outgoing.volume = 1f - progress
            incoming.volume = progress
            if (progress >= 1f) {
                completeCrossfade()
            } else {
                crossfadeHandler.postDelayed(this, CROSSFADE_TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
        serviceScope.launch(Dispatchers.IO) {
            playbackHttpClient = BockMediaApp.get(this@LocalPlaybackService).buildPlaybackHttpClient()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> {
                urls = intent.getStringArrayListExtra(EXTRA_URLS).orEmpty()
                titles = intent.getStringArrayListExtra(EXTRA_TITLES).orEmpty()
                artists = intent.getStringArrayListExtra(EXTRA_ARTISTS).orEmpty()
                albums = intent.getStringArrayListExtra(EXTRA_ALBUMS).orEmpty()
                paths = intent.getStringArrayListExtra(EXTRA_PATHS).orEmpty()
                durationsMs = intent.getLongArrayExtra(EXTRA_DURATIONS_MS)?.toList()
                    ?: List(paths.size) { 0L }
                crossfadeMs = intent.getLongExtra(EXTRA_CROSSFADE_MS, 0L).coerceAtLeast(0)
                activeTargetKind = intent.getStringExtra(EXTRA_TARGET_KIND)
                activeTargetPath = intent.getStringExtra(EXTRA_TARGET_PATH)
                activeTargetArtist = intent.getStringExtra(EXTRA_TARGET_ARTIST)
                activeTargetAlbum = intent.getStringExtra(EXTRA_TARGET_ALBUM)
                activeTargetPlaylistId = intent.getStringExtra(EXTRA_TARGET_PLAYLIST_ID)
                lastReportedIndex = -1
                consecutivePlayErrors = 0
                consecutiveSkips = 0
                retryErrorIndex = -1
                releasePlayers()
                // Satisfy startForegroundService() contract before async HTTP/player setup.
                promoteToForeground(buildNotification())
                val start = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceAtLeast(0)
                val shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                serviceScope.launch {
                    withContext(Dispatchers.IO) { ensurePlaybackHttpClient() }
                    startPlayback(urls, start, shuffle)
                }
            }
            ACTION_TOGGLE -> {
                if (player == null) {
                    val st = LocalPlaybackController.state.value
                    if (st.active && st.tracks.isNotEmpty()) {
                        serviceScope.launch {
                            LocalPlaybackController.playTracks(
                                this@LocalPlaybackService,
                                st.tracks,
                                startIndex = st.index,
                                shuffle = st.shuffle,
                                sourceLabel = st.sourceLabel,
                                playlist = st.playlist,
                                playlistId = st.playlistId,
                                activeTarget = st.activeTarget,
                            )
                            if (!st.isPlaying) player?.pause()
                        }
                    }
                    return START_STICKY
                }
                if (crossfading) {
                    val playing = incomingPlayer?.isPlaying == true
                    if (playing) {
                        incomingPlayer?.pause()
                        player?.pause()
                    } else {
                        resumePlayback()
                    }
                } else if (player?.isPlaying == true) {
                    player?.pause()
                } else {
                    resumePlayback()
                }
            }
            ACTION_NEXT -> skip(forward = true)
            ACTION_PREVIOUS -> skip(forward = false)
            ACTION_SEEK -> player?.let {
                cancelCrossfade(releaseIncoming = true)
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0)
                it.seekTo(pos)
                syncState(it)
            }
            ACTION_SET_SHUFFLE -> player?.let {
                cancelCrossfade(releaseIncoming = true)
                it.shuffleModeEnabled = intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                syncState(it)
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_STICKY
    }

    /** Drop ExoPlayer instances so a new queue never inherits ENDED/ crossfade state. */
    private fun releasePlayers() {
        crossfadeHandler.removeCallbacks(crossfadeVolumeRunnable)
        crossfading = false
        crossfadeTargetIndex = -1
        stopProgressUpdates()
        incomingPlayer?.release()
        incomingPlayer = null
        player?.release()
        player = null
        releaseMediaSession()
    }

    private fun startPlayback(urlList: List<String>, startIndex: Int, shuffle: Boolean = false) {
        if (urlList.isEmpty()) return
        if (playbackHttpClient == null) {
            Log.e(TAG, "startPlayback: HTTP client not ready")
            LocalPlaybackController.update {
                it.copy(error = "Playback not ready — try again", loading = false, isPlaying = false)
            }
            return
        }
        lastMetadataIndex = -1
        notificationArtPath = null
        notificationArtBitmap = null
        val exo = player ?: buildPlayer().also { player = it }
        exo.shuffleModeEnabled = shuffle
        val items = urlList.mapIndexed { index, url -> mediaItemFor(index, url) }
        exo.setMediaItems(items, startIndex.coerceAtMost(items.lastIndex), 0)
        exo.volume = 1f
        requestAudioFocus()
        exo.prepare()
        exo.play()
        reportPlayIfNeeded(startIndex.coerceAtMost(items.lastIndex))
        ensureDisplayedMetadata(exo)
        NowPlayingNotificationManager.stop(this)
        startProgressUpdates()
        postPlaybackNotification(force = true)
    }

    private fun trackMetadata(index: Int): MediaMetadata {
        val title = titles.getOrNull(index).orEmpty().ifBlank { "Unknown" }
        val artist = artists.getOrNull(index).orEmpty().ifBlank { "Unknown artist" }
        val album = albums.getOrNull(index).orEmpty()
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.takeIf { it.isNotBlank() })
            .setIsPlayable(true)
            .build()
    }

    private fun mediaItemFor(index: Int, url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(trackMetadata(index))
            .build()

    /** Keep library title/artist on AVRCP when the stream has no ID3 tags. */
    private fun ensureDisplayedMetadata(exo: ExoPlayer, force: Boolean = false) {
        val idx = exo.currentMediaItemIndex
        if (idx !in urls.indices) return
        val playerMd = exo.mediaMetadata
        val missing = playerMd.title.isNullOrBlank() || playerMd.artist.isNullOrBlank()
        if (!force && !missing && idx == lastMetadataIndex) return
        exo.replaceMediaItem(idx, mediaItemFor(idx, urls[idx]))
        lastMetadataIndex = idx
    }

    private fun postPlaybackNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAtMs < 1000L) return
        lastNotificationAtMs = now
        updatePlaybackNotification(buildNotification())
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    /**
     * Promote to foreground once, then update via [NotificationManager.notify]. Re-calling
     * [startForeground] on every crossfade/track change fails on Android 12+ when backgrounded
     * (`mAllowStartForeground false`) and the system eventually kills the service.
     */
    private fun updatePlaybackNotification(notification: Notification) {
        if (inForeground) {
            notificationManager()?.notify(NOTIFICATION_ID, notification)
            return
        }
        if (foregroundPromotionBlocked) {
            notificationManager()?.notify(NOTIFICATION_ID, notification)
            return
        }
        promoteToForeground(notification)
    }

    private fun promoteToForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        inForeground = true
        foregroundPromotionBlocked = false
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground rejected; posting notification only", e)
        foregroundPromotionBlocked = true
        notificationManager()?.notify(NOTIFICATION_ID, notification)
        false
    }

    private fun leadPlayer(): ExoPlayer? = if (crossfading) incomingPlayer ?: player else player

    private fun checkCrossfadeTrigger(exo: ExoPlayer) {
        if (!LocalPlaybackCrossfadePolicy.mayStartCrossfade(
                crossfadeMs, crossfading, exo.isPlaying, AppForegroundState.isInForeground,
            )) return
        // Only crossfade once ExoPlayer knows the real stream duration — library
        // metadata is often 0/wrong and would trigger an immediate skip to the next track.
        val duration = LocalPlaybackDuration.effectiveDurationMs(
            exo.duration,
            durationsMs.getOrNull(exo.currentMediaItemIndex) ?: 0L,
        )
        if (duration <= 0 || duration <= crossfadeMs) return
        if (exo.currentPosition < CROSSFADE_MIN_PLAYED_MS) return
        if (!exo.hasNextMediaItem()) return
        val remaining = duration - exo.currentPosition
        if (remaining <= crossfadeMs) {
            startCrossfade(exo, remaining.coerceAtMost(crossfadeMs))
        }
    }

    private fun startCrossfade(outgoing: ExoPlayer, overlapMs: Long) {
        if (crossfading || overlapMs <= 0) return
        val nextIndex = outgoing.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || nextIndex !in urls.indices) return
        crossfading = true
        crossfadeTargetIndex = nextIndex
        crossfadeDurationMs = overlapMs.coerceAtLeast(CROSSFADE_TICK_MS)
        crossfadeStartedAtMs = System.currentTimeMillis()
        // Don't let the outgoing player auto-advance to the next item while incoming crossfades in.
        outgoing.pauseAtEndOfMediaItems = true
        // Give the incoming player the FULL queue with absolute indices so that once
        // it's promoted, currentMediaItemIndex still lines up with paths/titles/etc.
        // and it keeps auto-advancing through the rest of the playlist.
        val incoming = buildSecondaryPlayer().also { incomingPlayer = it }
        val items = urls.mapIndexed { i, url -> mediaItemFor(i, url) }
        incoming.setMediaItems(items, nextIndex, 0)
        incoming.volume = 0f
        incoming.prepare()
        incoming.play()
        reportPlayIfNeeded(nextIndex)
        crossfadeHandler.removeCallbacks(crossfadeVolumeRunnable)
        crossfadeHandler.post(crossfadeVolumeRunnable)
        postPlaybackNotification(force = true)
    }

    private fun completeCrossfade() {
        crossfadeHandler.removeCallbacks(crossfadeVolumeRunnable)
        val incoming = incomingPlayer ?: run {
            crossfading = false
            crossfadeTargetIndex = -1
            return
        }
        val outgoing = player
        crossfadeTargetIndex = -1
        player = incoming
        incomingPlayer = null
        crossfading = false
        incoming.pauseAtEndOfMediaItems = false
        incoming.volume = 1f
        swapMediaSessionPlayer(incoming)
        outgoing?.release()
        if (incoming.playWhenReady && !incoming.isPlaying) {
            when (incoming.playbackState) {
                Player.STATE_IDLE -> incoming.prepare()
                Player.STATE_ENDED -> incoming.seekToDefaultPosition()
            }
            incoming.play()
        }
        Log.i(
            TAG,
            "completeCrossfade index=${incoming.currentMediaItemIndex} playing=${incoming.isPlaying} state=${incoming.playbackState}",
        )
        reportPlayIfNeeded(incoming.currentMediaItemIndex)
        syncState(incoming)
        postPlaybackNotification(force = true)
    }

    private fun cancelCrossfade(releaseIncoming: Boolean) {
        crossfadeHandler.removeCallbacks(crossfadeVolumeRunnable)
        crossfading = false
        crossfadeTargetIndex = -1
        player?.volume = 1f
        if (releaseIncoming) {
            incomingPlayer?.release()
            incomingPlayer = null
        }
    }

    private fun skip(forward: Boolean) {
        cancelCrossfade(releaseIncoming = true)
        val exo = player ?: return
        val shouldPlay = exo.playWhenReady || exo.isPlaying
        if (forward) exo.seekToNextMediaItem() else exo.seekToPreviousMediaItem()
        exo.volume = 1f
        when (exo.playbackState) {
            Player.STATE_ENDED -> exo.seekToDefaultPosition()
            Player.STATE_IDLE -> exo.prepare()
        }
        if (shouldPlay) {
            requestAudioFocus()
            exo.play()
        } else {
            exo.pause()
        }
        reportPlayIfNeeded(exo.currentMediaItemIndex)
        syncState(exo)
        startProgressUpdates()
        postPlaybackNotification(force = true)
    }

    /** Resume after pause or skip-while-paused; handles IDLE/ENDED so play() actually starts. */
    private fun resumePlayback() {
        cancelCrossfade(releaseIncoming = true)
        val exo = leadPlayer() ?: player ?: return
        requestAudioFocus()
        when (exo.playbackState) {
            Player.STATE_ENDED -> exo.seekToDefaultPosition()
            Player.STATE_IDLE -> exo.prepare()
        }
        exo.play()
        if (crossfading) incomingPlayer?.play()
        syncState(leadPlayer() ?: exo)
        startProgressUpdates()
        postPlaybackNotification(force = true)
    }

    private fun stopPlayback() {
        releasePlayers()
        abandonAudioFocus()
        DeviceAnalyticsReporter.clearPlayback(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        foregroundPromotionBlocked = false
        LocalPlaybackController.update { LocalPlaybackState() }
        stopSelf()
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
        val exo = buildExoPlayer()
        exo.addListener(primaryPlayerListener(exo))
        attachMediaSession(exo)
        return exo
    }

    private fun buildSecondaryPlayer(): ExoPlayer {
        // Uses the same listener as the primary player. Its callbacks are no-ops while
        // crossfading (guarded below), and once promoted it drives transitions,
        // notifications, and the next crossfade just like the original player.
        val exo = buildExoPlayer()
        exo.addListener(primaryPlayerListener(exo))
        return exo
    }

    private suspend fun ensurePlaybackHttpClient() {
        if (playbackHttpClient != null) return
        playbackHttpClient = BockMediaApp.get(this).buildPlaybackHttpClient()
    }

    private fun buildExoPlayer(): ExoPlayer {
        val httpClient = playbackHttpClient
            ?: error("Playback HTTP client not ready — call ensurePlaybackHttpClient() first")
        val dataSourceFactory = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(httpClient))
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
            )
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    // Smaller buffers — start playback sooner on cellular / Bluetooth head units.
                    .setBufferDurationsMs(2_500, 30_000, 800, 1_500)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Focus is managed once at the service level (see requestAudioFocus). Letting
                // each ExoPlayer grab focus would make the incoming player steal it from the
                // outgoing one mid-crossfade, pausing it — so the two tracks never truly overlap.
                false,
            )
            .build()
    }

    private fun requestAudioFocus() {
        val am = audioManager
            ?: (getSystemService(AudioManager::class.java)).also { audioManager = it }
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
        resumeOnFocusGain = false
    }

    private fun pauseAllPlayers() {
        player?.pause()
        incomingPlayer?.pause()
    }

    private fun playAllPlayers() {
        player?.play()
        if (crossfading) incomingPlayer?.play()
    }

    private fun primaryPlayerListener(exo: ExoPlayer) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (exo === leadPlayer()) {
                Log.i(TAG, "isPlaying=$isPlaying index=${exo.currentMediaItemIndex} crossfading=$crossfading state=${exo.playbackState}")
            }
            if (isPlaying && exo === leadPlayer()) {
                consecutivePlayErrors = 0
                consecutiveSkips = 0
                retryErrorIndex = -1
                LocalPlaybackController.update { it.copy(error = null) }
            }
            syncState(leadPlayer() ?: exo)
            if (isPlaying) startProgressUpdates() else if (!crossfading) stopProgressUpdates()
            postPlaybackNotification()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (exo !== leadPlayer() && !(crossfading && exo === incomingPlayer)) return
            if (mediaMetadata.title.isNullOrBlank() || mediaMetadata.artist.isNullOrBlank()) {
                ensureDisplayedMetadata(exo, force = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error index=${exo.currentMediaItemIndex} ${titles.getOrNull(exo.currentMediaItemIndex)}", error)
            if (crossfading) {
                if (exo === incomingPlayer) {
                    val failedIdx = crossfadeTargetIndex.takeIf { it >= 0 }
                        ?: exo.currentMediaItemIndex
                    cancelCrossfade(releaseIncoming = true)
                    player?.let { outgoing ->
                        outgoing.pauseAtEndOfMediaItems = false
                        outgoing.volume = 1f
                        skipFailedQueueIndex(outgoing, failedIdx, error)
                    }
                } else {
                    // Outgoing hit EOF/read error while incoming is playing — promote it.
                    incomingPlayer?.let { completeCrossfade() }
                        ?: cancelCrossfade(releaseIncoming = true)
                }
                return
            }
            handlePlaybackError(exo, error)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (crossfading) return
            consecutivePlayErrors = 0
            retryErrorIndex = -1
            ensureDisplayedMetadata(exo, force = true)
            syncState(exo)
            reportPlayIfNeeded(exo.currentMediaItemIndex)
            postPlaybackNotification(force = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (crossfading) return
            syncState(exo)
            postPlaybackNotification()
            if (playbackState == Player.STATE_ENDED) {
                val idx = exo.currentMediaItemIndex
                val libraryDur = durationsMs.getOrNull(idx) ?: 0L
                if (exo.hasNextMediaItem() &&
                    LocalPlaybackDuration.endedPrematurely(exo.currentPosition, libraryDur)
                ) {
                    handlePlaybackError(
                        exo,
                        PlaybackException(
                            "Stream ended early",
                            null,
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        ),
                    )
                    return
                }
                if (exo.hasNextMediaItem()) {
                    if (exo.playWhenReady) {
                        // ExoPlayer usually auto-advances; nudge in case it stalled.
                        exo.seekToNextMediaItem()
                        when (exo.playbackState) {
                            Player.STATE_IDLE -> exo.prepare()
                            Player.STATE_ENDED -> exo.seekToDefaultPosition()
                        }
                        exo.play()
                    }
                    reportPlayIfNeeded(exo.currentMediaItemIndex)
                    startProgressUpdates()
                    return
                }
                if (!exo.playWhenReady) return
                if (tryContinueQueue()) return
                stopPlayback()
            }
        }
    }

    private fun isSourceFormatError(error: PlaybackException): Boolean {
        val cause = error.cause?.javaClass?.simpleName.orEmpty()
        return cause.contains("UnrecognizedInputFormat", ignoreCase = true) ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
    }

    private suspend fun rebuildStreamUrlOnIo(index: Int): String? {
        if (index !in paths.indices) return null
        val path = paths[index]
        if (path.isBlank()) return null
        val app = BockMediaApp.get(this)
        val base = app.repository.peekBaseUrl() ?: app.resolveBaseUrl()
        return com.bockmedia.console.data.local.AppPreferences.streamUrl(
            base = base,
            filepath = path,
            title = titles.getOrNull(index),
            artist = artists.getOrNull(index),
            lowBandwidth = LocalPlaybackDuration.needsStreamTranscode(path),
            mediaSignSecret = app.preferences.mobileTokenNow(),
        )
    }

    private fun rebuildStreamUrl(index: Int): String? =
        runBlocking(Dispatchers.IO) { rebuildStreamUrlOnIo(index) }

    private fun skipFailedQueueIndex(exo: ExoPlayer, failedIdx: Int, error: PlaybackException) {
        val skipped = titles.getOrNull(failedIdx).orEmpty().ifBlank { "Track" }
        consecutiveSkips++
        if (consecutiveSkips >= MAX_CONSECUTIVE_PLAY_ERRORS) {
            LocalPlaybackController.update {
                it.copy(error = "Playback stopped after several errors (last: $skipped)")
            }
            stopPlayback()
            return
        }
        val nextIdx = failedIdx + 1
        if (nextIdx !in urls.indices) {
            LocalPlaybackController.update {
                it.copy(error = error.localizedMessage ?: "Playback failed")
            }
            stopPlayback()
            return
        }
        LocalPlaybackController.update {
            it.copy(error = "Skipped: $skipped")
        }
        exo.seekTo(nextIdx, 0L)
        when (exo.playbackState) {
            Player.STATE_IDLE -> exo.prepare()
            Player.STATE_ENDED -> exo.seekToDefaultPosition()
        }
        requestAudioFocus()
        exo.play()
        reportPlayIfNeeded(exo.currentMediaItemIndex)
        syncState(exo)
        startProgressUpdates()
        postPlaybackNotification(force = true)
    }

    private fun handlePlaybackError(exo: ExoPlayer, error: PlaybackException) {
        cancelCrossfade(releaseIncoming = true)
        val active = exo
        val idx = active.currentMediaItemIndex
        val isRetry = idx == retryErrorIndex
        if (!isRetry) {
            retryErrorIndex = idx
            consecutivePlayErrors = 0
        }
        consecutivePlayErrors++
        // Retry the same track once (transient network / slow server).
        if (consecutivePlayErrors == 1) {
            if (isSourceFormatError(error) && rebuildStreamUrl(idx)?.let { rebuilt ->
                    if (rebuilt != urls[idx]) {
                        urls = urls.toMutableList().also { it[idx] = rebuilt }
                        active.replaceMediaItem(idx, mediaItemFor(idx, rebuilt))
                    }
                    true
                } == true) {
                // fall through to retry play
            }
            active.seekToDefaultPosition()
            when (active.playbackState) {
                Player.STATE_IDLE -> active.prepare()
                Player.STATE_ENDED -> active.seekToDefaultPosition()
            }
            requestAudioFocus()
            active.play()
            syncState(active)
            startProgressUpdates()
            postPlaybackNotification(force = true)
            return
        }
        val skipped = titles.getOrNull(idx).orEmpty().ifBlank { "Track" }
        consecutiveSkips++
        if (consecutiveSkips >= MAX_CONSECUTIVE_PLAY_ERRORS) {
            LocalPlaybackController.update {
                it.copy(error = "Playback stopped after several errors (last: $skipped)")
            }
            stopPlayback()
            return
        }
        if (!active.hasNextMediaItem()) {
            LocalPlaybackController.update {
                it.copy(error = error.localizedMessage ?: "Playback failed")
            }
            stopPlayback()
            return
        }
        LocalPlaybackController.update {
            it.copy(error = "Skipped: $skipped")
        }
        active.seekToNextMediaItem()
        when (active.playbackState) {
            Player.STATE_IDLE -> active.prepare()
            Player.STATE_ENDED -> active.seekToDefaultPosition()
        }
        requestAudioFocus()
        active.play()
        reportPlayIfNeeded(active.currentMediaItemIndex)
        syncState(active)
        startProgressUpdates()
        postPlaybackNotification(force = true)
    }

    private fun tryContinueQueue(): Boolean {
        serviceScope.launch {
            val ok = withContext(Dispatchers.IO) { tryContinueQueueOnIo() }
            if (!ok) stopPlayback()
        }
        return true
    }

    private suspend fun tryContinueQueueOnIo(): Boolean {
        val app = BockMediaApp.get(this)
        val mode = app.preferences.getContinueAfterQueueSync()
        if (mode == "off" || activeTargetKind == "radio") return false
        val idx = exoCurrentIndex()
        val lastPath = paths.getOrNull(idx).orEmpty()
        if (lastPath.isBlank()) return false
        val target = when (activeTargetKind) {
            "playlist" -> activeTargetPlaylistId?.let { PlayTarget.Playlist(it, "") }
            "album" -> activeTargetAlbum?.let { PlayTarget.Album(it, activeTargetArtist) }
            "artist" -> activeTargetArtist?.let { PlayTarget.Artist(it) }
            "song" -> activeTargetPath?.let { PlayTarget.Song(it, titles.getOrNull(idx).orEmpty()) }
            else -> null
        }
        val more = app.repository.continuationTracks(
            mode = mode,
            target = target,
            lastPath = lastPath,
            lastArtist = artists.getOrNull(idx),
            exclude = paths.toSet(),
        )
        if (more.isEmpty()) return false
        val base = app.resolveBaseUrl()
        val signSecret = app.preferences.mobileTokenNow()
        val appended = more.mapNotNull { track ->
            val url = track.localFile?.let { android.net.Uri.fromFile(it).toString() }
                ?: com.bockmedia.console.data.local.AppPreferences.streamUrl(
                    base, track.path, track.title, track.artist,
                    lowBandwidth = LocalPlaybackDuration.needsStreamTranscode(track.path),
                    mediaSignSecret = signSecret,
                )
            url?.let { track to it }
        }
        if (appended.isEmpty()) return false
        withContext(Dispatchers.Main) {
            paths = paths + appended.map { it.first.path }
            titles = titles + appended.map { it.first.title }
            artists = artists + appended.map { it.first.displayArtist }
            albums = albums + appended.map { it.first.album.orEmpty() }
            durationsMs = durationsMs + appended.map { it.first.durationMs }
            urls = urls + appended.map { it.second }
            val exo = leadPlayer() ?: player ?: return@withContext
            val items = urls.mapIndexed { i, url -> mediaItemFor(i, url) }
            val nextIndex = idx + 1
            exo.setMediaItems(items, nextIndex, 0)
            exo.prepare()
            exo.play()
            reportPlayIfNeeded(nextIndex)
            syncState(exo)
            LocalPlaybackController.update { state ->
                state.copy(
                    tracks = state.tracks + appended.map { it.first },
                    index = nextIndex,
                    isPlaying = true,
                )
            }
            startProgressUpdates()
            postPlaybackNotification(force = true)
        }
        return true
    }

    private fun exoCurrentIndex(): Int = leadPlayer()?.currentMediaItemIndex ?: 0

    private fun effectiveDurationMs(exo: ExoPlayer, index: Int): Long =
        LocalPlaybackDuration.effectiveDurationMs(
            exo.duration,
            durationsMs.getOrNull(index) ?: 0L,
        )

    /** Point the long-lived session at [exo] without release/recreate (FGS-safe). */
    private fun swapMediaSessionPlayer(exo: ExoPlayer) {
        val session = mediaSession
        if (session != null) {
            session.setPlayer(exo)
        } else {
            attachMediaSession(exo)
        }
    }

    private fun attachMediaSession(exo: ExoPlayer) {
        if (mediaSession != null) {
            swapMediaSessionPlayer(exo)
            return
        }
        val session = MediaSession.Builder(this, exo)
            .setId("bock-local-playback")
            .build()
        mediaSession = session
        addSession(session)
    }

    private fun releaseMediaSession() {
        mediaSession?.let { session ->
            if (isSessionAdded(session)) removeSession(session)
            session.release()
        }
        mediaSession = null
    }

    private fun syncMediaSessionIfNeeded() {
        val exo = player ?: return
        swapMediaSessionPlayer(exo)
        postPlaybackNotification(force = true)
    }

    override fun onUpdateNotification(session: MediaSession) {
        onUpdateNotification(session, startInForegroundRequired = false)
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val notification = buildNotification()
        if (startInForegroundRequired && AppForegroundState.isInForeground) {
            inForeground = false
            foregroundPromotionBlocked = false
            promoteToForeground(notification)
        } else {
            updatePlaybackNotification(notification)
        }
    }

    private fun syncState(exo: ExoPlayer) {
        val idx = exo.currentMediaItemIndex
        val durationMs = effectiveDurationMs(exo, idx)
        LocalPlaybackController.onServiceState(
            index = idx,
            isPlaying = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = durationMs,
            shuffle = player?.shuffleModeEnabled == true,
        )
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
        val exo = leadPlayer()
        val idx = exo?.currentMediaItemIndex ?: 0
        val title = titles.getOrNull(idx).orEmpty().ifBlank { "Unknown" }
        val artist = artists.getOrNull(idx).orEmpty().ifBlank { "Unknown artist" }
        val album = albums.getOrNull(idx).orEmpty()
        val isPlaying = exo?.isPlaying == true
        val wantsPlay = exo?.playWhenReady == true
        val buffering = exo?.playbackState == Player.STATE_BUFFERING
        val duration = if (exo != null) effectiveDurationMs(exo, idx) else 0L
        val position = exo?.currentPosition?.coerceAtLeast(0) ?: 0L
        val hasDuration = duration > 0

        val prev = actionPendingIntent(ACTION_PREVIOUS, 1)
        val toggle = actionPendingIntent(ACTION_TOGGLE, 2)
        val next = actionPendingIntent(ACTION_NEXT, 3)

        val localTrack = LocalPlaybackController.state.value.tracks.getOrNull(idx)
        val artBitmap = notificationArtFor(localTrack)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .apply {
                if (album.isNotBlank()) setSubText(album)
            }
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying || buffering || wantsPlay)
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

    /** Decode embedded art once per track — buildNotification runs often during playback. */
    private fun notificationArtFor(track: com.bockmedia.console.domain.model.LocalTrack?): android.graphics.Bitmap? {
        val file = track?.localFile ?: return null
        val path = track.path
        if (path == notificationArtPath) return notificationArtBitmap
        notificationArtPath = path
        notificationArtBitmap = PlaybackArtwork.embeddedArtUri(this, file)
            ?.removePrefix("file://")
            ?.let { artPath -> runCatching { BitmapFactory.decodeFile(artPath) }.getOrNull() }
        return notificationArtBitmap
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
        if (instance === this) instance = null
        cancelCrossfade(releaseIncoming = true)
        abandonAudioFocus()
        stopProgressUpdates()
        player?.release()
        releaseMediaSession()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: LocalPlaybackService? = null

        fun hasPlayer(): Boolean = instance?.player != null

        fun onAppForeground() {
            instance?.syncMediaSessionIfNeeded()
        }

        const val ACTION_PLAY = "com.bockmedia.console.local.PLAY"
        const val ACTION_TOGGLE = "com.bockmedia.console.local.TOGGLE"
        const val ACTION_NEXT = "com.bockmedia.console.local.NEXT"
        const val ACTION_PREVIOUS = "com.bockmedia.console.local.PREVIOUS"
        const val ACTION_STOP = "com.bockmedia.console.local.STOP"
        const val ACTION_SET_SHUFFLE = "com.bockmedia.console.local.SET_SHUFFLE"
        const val ACTION_SEEK = "com.bockmedia.console.local.SEEK"
        const val EXTRA_URLS = "urls"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_ARTISTS = "artists"
        const val EXTRA_ALBUMS = "albums"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_DURATIONS_MS = "durationsMs"
        const val EXTRA_START_INDEX = "startIndex"
        const val EXTRA_CROSSFADE_MS = "crossfadeMs"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_TARGET_KIND = "targetKind"
        const val EXTRA_TARGET_PATH = "targetPath"
        const val EXTRA_TARGET_ARTIST = "targetArtist"
        const val EXTRA_TARGET_ALBUM = "targetAlbum"
        const val EXTRA_TARGET_PLAYLIST_ID = "targetPlaylistId"
        private const val CHANNEL_ID = "local_playback"
        private const val NOTIFICATION_ID = 42
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CROSSFADE_TICK_MS = 50L
        private const val CROSSFADE_MIN_PLAYED_MS = 15_000L
        private const val MAX_CONSECUTIVE_PLAY_ERRORS = 8
        private const val TAG = "LocalPlayback"
    }
}
