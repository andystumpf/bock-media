package com.bockmedia.console.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bockmedia.console.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OfflineDownloadForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        promoteToForeground()
        observeStatuses()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        if (!observing) observeStatuses()
        return START_STICKY
    }

    override fun onDestroy() {
        observing = false
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", 0f))
    }

    private fun observeStatuses() {
        if (observing) return
        observing = true
        scope.launch {
            OfflineDownloadManager.statuses.collectLatest { statuses ->
                val active = statuses.values.filter { it.state == DownloadState.Downloading }
                if (active.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val aggregate = active.map { it.progress }.average().toFloat()
                val title = if (active.size == 1) {
                    "Downloading \"${active.first().manifest.title}\""
                } else {
                    "Downloading ${active.size} collections"
                }
                val cancelId = active.singleOrNull()?.manifest?.id
                startForeground(NOTIFICATION_ID, buildNotification(title, aggregate, cancelId))
            }
        }
    }

    private fun buildNotification(title: String, progress: Float, cancelId: String? = null): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            MainActivity.launchIntent(this, "downloads"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(title)
            .setContentText("${(progress * 100).toInt()}% complete")
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        cancelId?.let { id ->
            val cancelIntent = Intent(this, OfflineDownloadActionReceiver::class.java).apply {
                action = OfflineDownloadActionReceiver.ACTION_CANCEL
                putExtra(OfflineDownloadActionReceiver.EXTRA_COLLECTION_ID, id)
            }
            val cancelPi = PendingIntent.getBroadcast(
                this,
                id.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Offline downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while music is saved for offline playback"
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "offline_downloads"
        private const val NOTIFICATION_ID = 4202

        fun start(context: Context) {
            val intent = Intent(context, OfflineDownloadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
