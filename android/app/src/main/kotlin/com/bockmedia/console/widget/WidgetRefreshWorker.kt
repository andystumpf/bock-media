package com.bockmedia.console.widget

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object WidgetRefreshScheduler {
    private const val WORK_NAME = "bockmedia_widget_refresh"

    fun schedule(context: Context) {
        val playing = NowPlayingSessionStore.snapshot?.items?.any { !it.paused } == true
        val delaySec = if (playing) 5L else 30L
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        NowPlayingWidget.updateAll(applicationContext)
        WidgetRefreshScheduler.schedule(applicationContext)
        return Result.success()
    }
}
