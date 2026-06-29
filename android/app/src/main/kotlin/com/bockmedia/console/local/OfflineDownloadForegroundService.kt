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
        if (!promoteToForeground()) {
            stopSelf()
            return
        }
        observeStatuses()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!observing) observeStatuses()
        return START_STICKY
    }

    override fun onDestroy() {
        observing = false
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Enter the foreground for the download notification. On Android 14+ a `dataSync`
     * foreground service has a 6h/24h budget; once it's exhausted (or the service was
     * started from the background) [startForeground] throws and would otherwise crash
     * the whole app. Swallow it — downloads keep running on OfflineDownloadManager's
     * own scope, just without the progress notification.
     *
     * @return true if the service successfully entered the foreground.
     */
    private fun promoteToForeground(): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", 0f))
        foregroundUnavailable = false
        true
    } catch (_: Exception) {
        foregroundUnavailable = true
        false
    }

    private fun observeStatuses() {
        if (observing) return
        observing = true
        scope.launch {
            OfflineDownloadManager.statuses.collectLatest { statuses ->
                val visibleIds = OfflineDownloadSync.visibleCollectionIds(this@OfflineDownloadForegroundService)
                val active = statuses.values.filter {
                    it.manifest.id in visibleIds && it.state == DownloadState.Downloading
                }
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
                runCatching {
                    startForeground(NOTIFICATION_ID, buildNotification(title, aggregate, cancelId))
                }
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

        /** Set when startForeground() is rejected (Android 14+ dataSync budget exhausted). */
        @Volatile
        private var foregroundUnavailable = false

        fun start(context: Context) {
            val intent = Intent(context, OfflineDownloadForegroundService::class.java)
            // A plain startService() carries no "must call startForeground() within the
            // timeout" contract, so it can never trigger ForegroundServiceDidNotStartInTime.
            // It succeeds while the app is in the foreground (the common download/resume
            // case). If it's rejected because the app is in the background, fall back to
            // startForegroundService — unless we already know foreground promotion is
            // blocked (budget exhausted), in which case we skip it entirely so we never
            // violate the FGS contract and crash. Downloads keep running on
            // OfflineDownloadManager's own scope regardless; only the notification is lost.
            if (runCatching { context.startService(intent) }.isSuccess) return
            if (foregroundUnavailable) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(intent) }
            }
        }
    }
}
