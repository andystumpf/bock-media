package com.bockmedia.console.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.R
import kotlinx.coroutines.runBlocking

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NowPlayingWidget.ACTION_REFRESH -> NowPlayingWidget.updateAll(context)
            NowPlayingWidget.ACTION_PAUSE -> control(context, intent, "pause")
            NowPlayingWidget.ACTION_PLAY -> control(context, intent, "play")
            NowPlayingWidget.ACTION_NEXT -> control(context, intent, "next")
        }
    }

    private fun control(context: Context, intent: Intent, action: String) {
        val deviceId = intent.getStringExtra(NowPlayingWidget.EXTRA_DEVICE_ID) ?: return
        val deviceName = intent.getStringExtra(NowPlayingWidget.EXTRA_DEVICE_NAME) ?: return
        val pending = goAsync()
        Thread {
            val ok = runCatching {
                runBlocking {
                    BockMediaApp.get(context).repository.deviceControl(deviceId, deviceName, null, action)
                }
            }.isSuccess
            NowPlayingWidget.updateAll(context)
            if (!ok) Toast.makeText(context, R.string.widget_control_failed, Toast.LENGTH_SHORT).show()
            pending.finish()
        }.start()
    }
}
