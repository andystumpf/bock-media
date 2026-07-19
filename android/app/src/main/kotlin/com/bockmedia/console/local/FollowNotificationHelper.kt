package com.bockmedia.console.local

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.data.api.dto.FollowedNotificationsResponse

object FollowNotificationHelper {
    private const val CHANNEL_ID = "followed_artists"
    private const val NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.follow_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.follow_notification_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun maybeNotify(context: Context, response: FollowedNotificationsResponse) {
        if (response.unreadCount <= 0) return
        ensureChannel(context)
        val album = response.albums.firstOrNull()
        val title = when {
            response.unreadCount == 1 && album?.album != null ->
                "New from ${album.artist ?: "an artist you follow"}"
            else -> "${response.unreadCount} new releases from artists you follow"
        }
        val text = album?.let { "${it.album} · ${it.artist}" }
            ?: "Tap to see what's new in your library"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "home")
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
