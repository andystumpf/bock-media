package com.bockmedia.console.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import com.bockmedia.console.domain.model.formatPlaybackTime
import com.bockmedia.console.widget.NowPlayingSessionStore
import com.bockmedia.console.widget.NowPlayingWidget
import java.net.URL
import kotlin.math.max
import kotlin.math.min

object NowPlayingNotificationManager {

    const val CHANNEL_ID = "now_playing"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun sync(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val item = NowPlayingSessionStore.focusedItem()
        val snap = NowPlayingSessionStore.snapshot
        if (item == null || snap == null) {
            stop(appContext)
            return
        }
        val notification = build(appContext, item, snap.items.size, snap.controlsAvailable)
        NowPlayingMonitorService.start(appContext, notification)
    }

    fun stop(context: Context) {
        NowPlayingMonitorService.stop(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun build(
        context: Context,
        item: NowPlayingDeviceItem,
        deviceCount: Int,
        controlsAvailable: Boolean,
    ): Notification {
        val openPi = PendingIntent.getActivity(
            context,
            0,
            MainActivity.launchIntent(context, "#nowplaying"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val artistLine = listOfNotNull(item.artist, item.deviceName).joinToString(" · ")
        val prog = computeNowPlayingProgress(item.timestamp, item.duration_ms, item.offset_ms, item.paused)
        val elapsedSec = prog.elapsedMs / 1000
        val durationSec = prog.durationMs / 1000
        val positionText = if (durationSec > 0) {
            "${formatPlaybackTime(elapsedSec)} / ${formatPlaybackTime(durationSec)}"
        } else {
            formatPlaybackTime(elapsedSec)
        }

        val subtitle = buildString {
            append(artistLine)
            if (deviceCount > 1) {
                append(" · ")
                append(context.getString(R.string.notification_more_devices, deviceCount - 1))
            }
            append(" · ")
            append(positionText)
            if (item.paused) append(" · ${context.getString(R.string.widget_paused)}")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(item.track ?: context.getString(R.string.widget_unknown_track))
            .setContentText(subtitle)
            .setSubText(item.sourceLabel ?: item.playlist)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(!item.paused)
            .setShowWhen(false)
            .setLargeIcon(loadArtwork(context, item.filepath))

        val enabled = NowPlayingSessionStore.canControl(item)
        val deviceName = item.deviceName?.takeIf { it.isNotBlank() } ?: item.deviceId
        if (enabled) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                context.getString(R.string.notification_previous),
                NowPlayingWidget.pendingAction(
                    context, NowPlayingWidget.ACTION_PREVIOUS,
                    item.deviceId, deviceName, 9001,
                ),
            )
            val toggleAction = if (item.paused) NowPlayingWidget.ACTION_PLAY else NowPlayingWidget.ACTION_PAUSE
            val toggleIcon = if (item.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            val toggleLabel = if (item.paused) {
                context.getString(R.string.notification_play)
            } else {
                context.getString(R.string.notification_pause)
            }
            builder.addAction(
                toggleIcon,
                toggleLabel,
                NowPlayingWidget.pendingAction(
                    context, toggleAction,
                    item.deviceId, deviceName, 9002,
                ),
            )
            builder.addAction(
                android.R.drawable.ic_media_next,
                context.getString(R.string.notification_next),
                NowPlayingWidget.pendingAction(
                    context, NowPlayingWidget.ACTION_NEXT,
                    item.deviceId, deviceName, 9003,
                ),
            )
        }

        if (deviceCount > 1) {
            builder.addAction(
                android.R.drawable.ic_menu_rotate,
                context.getString(R.string.notification_next_device),
                NowPlayingWidget.pendingGlobalAction(context, NowPlayingWidget.ACTION_CYCLE_DEVICE, 9004),
            )
        }

        builder.setStyle(
            MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(NowPlayingMonitorService.sessionToken(context)),
        )

        if (durationSec > 0) {
            builder.setProgress(durationSec.toInt(), elapsedSec.toInt(), item.paused)
        }

        return builder.build()
    }

    private fun loadArtwork(context: Context, filepath: String?): Bitmap? {
        val base = NowPlayingSessionStore.snapshot?.baseUrl ?: return null
        val url = AppPreferences.artworkUrl(base, filepath) ?: return null
        return try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.getInputStream().use { stream ->
                val bmp = BitmapFactory.decodeStream(stream) ?: return null
                val size = 256
                val scale = min(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
                Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * scale).toInt()),
                    max(1, (bmp.height * scale).toInt()),
                    true,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
