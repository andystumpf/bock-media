package com.bockmedia.console.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import java.net.URL
import kotlin.math.max
import kotlin.math.min

object NowPlayingWidget {

    const val ACTION_REFRESH = "com.bockmedia.console.widget.REFRESH"
    const val ACTION_PAUSE = "com.bockmedia.console.widget.PAUSE"
    const val ACTION_PLAY = "com.bockmedia.console.widget.PLAY"
    const val ACTION_NEXT = "com.bockmedia.console.widget.NEXT"
    const val EXTRA_DEVICE_ID = "deviceId"
    const val EXTRA_DEVICE_NAME = "deviceName"

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) {
            WidgetRefreshScheduler.cancel(context)
            return
        }
        ids.forEach { updateOne(context, mgr, it) }
        WidgetRefreshScheduler.schedule(context)
    }

    fun updateOne(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        val app = BockMediaApp.get(context)
        val base = BockMediaApp.activeBaseUrlBlocking(context)

        if (base.isNullOrBlank()) {
            bindEmpty(views, context, widgetId, context.getString(R.string.widget_setup_required))
            mgr.updateAppWidget(widgetId, views)
            return
        }

        val snap = runCatching {
            kotlinx.coroutines.runBlocking {
                BockMediaApp.apiBlocking(context).nowPlayingDevices()
            }
        }.getOrNull()

        val item = snap?.items?.firstOrNull { !it.paused } ?: snap?.items?.firstOrNull()

        if (item == null) {
            bindEmpty(views, context, widgetId, context.getString(R.string.widget_nothing_playing))
            bindControls(views, context, widgetId, null, null, false, false)
            mgr.updateAppWidget(widgetId, views)
            return
        }

        views.setTextViewText(R.id.widget_track, item.track ?: context.getString(R.string.widget_unknown_track))
        views.setTextViewText(R.id.widget_subtitle, listOfNotNull(item.artist, item.deviceName).joinToString(" · "))
        item.sourceLabel?.let {
            views.setViewVisibility(R.id.widget_source, View.VISIBLE)
            views.setTextViewText(R.id.widget_source, it)
        } ?: views.setViewVisibility(R.id.widget_source, View.GONE)

        val prog = computeNowPlayingProgress(item.timestamp, item.duration_ms, item.offset_ms, item.paused)
        views.setProgressBar(R.id.widget_progress, 1000, (prog.fraction * 1000).toInt(), false)

        loadArtwork(base, views, mgr, widgetId, item.filepath)
        bindControls(views, context, widgetId, item.deviceId, item.deviceName, snap?.controlsAvailable == true, item.paused)
        views.setOnClickPendingIntent(R.id.widget_root, pendingOpen(context))
        views.setOnClickPendingIntent(R.id.widget_btn_open, pendingOpen(context))
        mgr.updateAppWidget(widgetId, views)
    }

    private fun bindEmpty(views: RemoteViews, context: Context, widgetId: Int, message: String) {
        views.setTextViewText(R.id.widget_track, context.getString(R.string.app_name))
        views.setTextViewText(R.id.widget_subtitle, message)
        views.setViewVisibility(R.id.widget_source, View.GONE)
        views.setProgressBar(R.id.widget_progress, 1000, 0, false)
        views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
        views.setOnClickPendingIntent(R.id.widget_root, pendingOpen(context))
        views.setOnClickPendingIntent(R.id.widget_btn_open, pendingOpen(context))
    }

    private fun bindControls(
        views: RemoteViews,
        context: Context,
        widgetId: Int,
        deviceId: String?,
        deviceName: String?,
        controlsAvailable: Boolean,
        paused: Boolean,
    ) {
        val enabled = controlsAvailable && !deviceId.isNullOrBlank() && !deviceName.isNullOrBlank()
        views.setViewVisibility(R.id.widget_controls, if (enabled) View.VISIBLE else View.GONE)
        if (!enabled) return
        val toggleAction = if (paused) ACTION_PLAY else ACTION_PAUSE
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        views.setImageViewResource(R.id.widget_btn_pause, toggleIcon)
        views.setOnClickPendingIntent(R.id.widget_btn_pause, pendingAction(context, toggleAction, deviceId!!, deviceName!!, widgetId))
        views.setOnClickPendingIntent(R.id.widget_btn_next, pendingAction(context, ACTION_NEXT, deviceId, deviceName, widgetId))
        views.setOnClickPendingIntent(R.id.widget_btn_refresh, pendingAction(context, ACTION_REFRESH, deviceId, deviceName, widgetId))
    }

    private fun loadArtwork(base: String, views: RemoteViews, mgr: AppWidgetManager, widgetId: Int, filepath: String?) {
        val url = AppPreferences.artworkUrl(base, filepath)
        if (url == null) {
            views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
            return
        }
        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val bmp = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                views.setImageViewBitmap(R.id.widget_art, scaleArt(bmp))
                mgr.updateAppWidget(widgetId, views)
            }
        } catch (_: Exception) {
            views.setImageViewResource(R.id.widget_art, R.mipmap.ic_launcher)
        }
    }

    private fun scaleArt(src: Bitmap): Bitmap {
        val size = 256
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        return Bitmap.createScaledBitmap(src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()), true)
    }

    private fun pendingOpen(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, MainActivity.launchIntent(context, "#nowplaying"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun pendingAction(context: Context, action: String, deviceId: String, deviceName: String, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(context, (action + deviceId + widgetId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
