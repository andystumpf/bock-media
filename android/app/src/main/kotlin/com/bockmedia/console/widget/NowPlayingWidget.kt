package com.bockmedia.console.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R
import com.bockmedia.console.media.NowPlayingNotificationManager

object NowPlayingWidget {

    const val ACTION_REFRESH = "com.bockmedia.console.widget.REFRESH"
    const val ACTION_PAUSE = "com.bockmedia.console.widget.PAUSE"
    const val ACTION_PLAY = "com.bockmedia.console.widget.PLAY"
    const val ACTION_NEXT = "com.bockmedia.console.widget.NEXT"
    const val ACTION_PREVIOUS = "com.bockmedia.console.widget.PREVIOUS"
    const val ACTION_CYCLE_DEVICE = "com.bockmedia.console.widget.CYCLE_DEVICE"
    const val ACTION_OPEN = "com.bockmedia.console.widget.OPEN"
    const val EXTRA_DEVICE_ID = "deviceId"
    const val EXTRA_DEVICE_NAME = "deviceName"

    fun updateAll(context: Context) = refreshSession(context)

    /** Fetch NP state and refresh widget/notification on the main thread (safe for MediaSession). */
    fun refreshSession(context: Context) {
        NowPlayingController.fetchAndStore(context)
        publishSession(context.applicationContext)
    }

    /** Push the current session snapshot to widget + notification (no network fetch). */
    fun publishSession(context: Context) {
        val appContext = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(ComponentName(appContext, NowPlayingWidgetProvider::class.java))
            if (ids.isEmpty()) {
                WidgetRefreshScheduler.cancel(appContext)
                NowPlayingNotificationManager.stop(appContext)
                return@post
            }
            ids.forEach { updateOne(appContext, mgr, it) }
            NowPlayingNotificationManager.sync(appContext)
            WidgetRefreshScheduler.schedule(appContext)
        }
    }

    fun updateWidgetsOnly(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
        ids.forEach { updateOne(context, mgr, it) }
    }

    fun updateOne(context: Context, mgr: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        val snap = NowPlayingSessionStore.snapshot
        val count = snap?.items?.size ?: 0

        if (snap == null || snap.baseUrl.isNullOrBlank()) {
            views.setViewVisibility(R.id.widget_list, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_setup_required))
            views.setTextViewText(R.id.widget_header, context.getString(R.string.widget_name))
        } else if (count == 0) {
            views.setViewVisibility(R.id.widget_list, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_nothing_playing))
            views.setTextViewText(R.id.widget_header, context.getString(R.string.widget_name))
        } else {
            views.setViewVisibility(R.id.widget_list, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            views.setTextViewText(
                R.id.widget_header,
                context.getString(R.string.widget_playing_count, count),
            )
            val svcIntent = Intent(context, NowPlayingWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_list, svcIntent)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, WidgetActionReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                ),
            )
            mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }

        views.setOnClickPendingIntent(R.id.widget_root, pendingOpen(context))
        mgr.updateAppWidget(widgetId, views)
    }

    fun pendingOpen(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            MainActivity.launchIntent(context, "#nowplaying"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun pendingAction(
        context: Context,
        action: String,
        deviceId: String,
        deviceName: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
        }
        return PendingIntent.getBroadcast(
            context,
            (action + deviceId + requestCode).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun pendingGlobalAction(context: Context, action: String, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            this.action = action
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            (action + requestCode).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
