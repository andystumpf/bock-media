package com.bockmedia.console.local

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class OfflineDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL) return
        val id = intent.getStringExtra(EXTRA_COLLECTION_ID) ?: return
        OfflineDownloadManager.cancelCollection(id)
    }

    companion object {
        const val ACTION_CANCEL = "com.bockmedia.console.download.CANCEL"
        const val EXTRA_COLLECTION_ID = "collectionId"
    }
}
