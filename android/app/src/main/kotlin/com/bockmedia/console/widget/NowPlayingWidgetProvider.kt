package com.bockmedia.console.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                appWidgetIds.forEach { NowPlayingWidget.updateOne(context, appWidgetManager, it) }
                WidgetRefreshScheduler.schedule(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onEnabled(context: Context) = WidgetRefreshScheduler.schedule(context)
    override fun onDisabled(context: Context) {
        WidgetRefreshScheduler.cancel(context)
    }
}
