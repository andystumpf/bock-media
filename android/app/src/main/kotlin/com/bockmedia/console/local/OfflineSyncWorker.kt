package com.bockmedia.console.local

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking

object OfflineSyncWorker {
    private const val WORK_NAME = "bockmedia_offline_sync"

    fun enqueue(context: Context) {
        val req = OneTimeWorkRequestBuilder<OfflineSyncWorkerImpl>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
    }
}

class OfflineSyncWorkerImpl(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = runBlocking {
        runCatching {
            OfflineDownloadManager.syncAll(applicationContext)
        }
        Result.success()
    }
}
