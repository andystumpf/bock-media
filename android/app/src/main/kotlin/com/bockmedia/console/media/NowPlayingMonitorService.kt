package com.bockmedia.console.media

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.bockmedia.console.widget.NowPlayingController
import com.bockmedia.console.widget.NowPlayingSessionStore
import com.bockmedia.console.widget.NowPlayingWidget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NowPlayingMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session(this), intent)
        if (intent == null) {
            val notification = NowPlayingNotificationManager.buildCurrent(this)
            return if (notification != null) {
                ServiceCompat.startForeground(
                    this,
                    NowPlayingNotificationManager.NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                updateSession()
                START_STICKY
            } else {
                stopSelf()
                START_NOT_STICKY
            }
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_NOTIFICATION)
                }
                if (notification != null) {
                    ServiceCompat.startForeground(
                        this,
                        NowPlayingNotificationManager.NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                    updateSession()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun updateSession() {
        val item = NowPlayingSessionStore.focusedItem() ?: return
        val session = session(this)
        session.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, item.track)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, item.artist)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM, item.album)
                .putLong(
                    android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION,
                    item.duration_ms,
                )
                .build(),
        )
        val canControl = NowPlayingSessionStore.canControl(item)
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE
        if (canControl) {
            actions = actions or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        val state = when {
            item.stopped -> PlaybackStateCompat.STATE_STOPPED
            item.paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, item.offset_ms, if (item.paused || item.stopped) 0f else 1f)
                .build(),
        )
    }

    companion object {
        private const val ACTION_START = "com.bockmedia.console.media.START"
        private const val ACTION_STOP = "com.bockmedia.console.media.STOP"
        private const val EXTRA_NOTIFICATION = "notification"

        @Volatile
        private var mediaSession: MediaSessionCompat? = null

        @Volatile
        private var appContext: Context? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun session(context: Context): MediaSessionCompat {
            mediaSession?.let { return it }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                return createSession(context.applicationContext)
            }
            val latch = CountDownLatch(1)
            var created: MediaSessionCompat? = null
            var error: RuntimeException? = null
            mainHandler.post {
                try {
                    created = createSession(context.applicationContext)
                } catch (e: RuntimeException) {
                    error = e
                } finally {
                    latch.countDown()
                }
            }
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw IllegalStateException("MediaSession init timed out")
            }
            error?.let { throw it }
            return created ?: throw IllegalStateException("MediaSession init failed")
        }

        private fun createSession(context: Context): MediaSessionCompat {
            synchronized(this) {
                mediaSession?.let { return it }
                appContext = context.applicationContext
                return MediaSessionCompat(context, "BockMediaNowPlaying").also { session ->
                    session.setFlags(
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
                    )
                    session.setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() = dispatchControl("play")
                        override fun onPause() = dispatchControl("pause")
                        override fun onSkipToNext() = dispatchControl("next")
                        override fun onSkipToPrevious() = dispatchControl("previous")
                    })
                    mediaSession = session
                    session.isActive = true
                }
            }
        }

        private fun dispatchControl(action: String) {
            val ctx = appContext ?: return
            val item = NowPlayingSessionStore.focusedItem() ?: return
            val deviceName = item.deviceName ?: return
            if (!NowPlayingSessionStore.canControl(item)) return
            Thread {
                NowPlayingController.sendControl(ctx, item.deviceId, deviceName, action)
            }.start()
        }

        fun sessionToken(context: Context) = session(context).sessionToken

        fun start(context: Context, notification: android.app.Notification) {
            val intent = Intent(context, NowPlayingMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NOTIFICATION, notification)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NowPlayingMonitorService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
