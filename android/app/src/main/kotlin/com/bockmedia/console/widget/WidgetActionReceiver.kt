package com.bockmedia.console.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import com.bockmedia.console.MainActivity
import com.bockmedia.console.R

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            NowPlayingWidget.ACTION_REFRESH -> NowPlayingWidget.updateAll(context)
            NowPlayingWidget.ACTION_PAUSE -> control(context, intent, "pause")
            NowPlayingWidget.ACTION_PLAY -> control(context, intent, "play")
            NowPlayingWidget.ACTION_NEXT -> control(context, intent, "next")
            NowPlayingWidget.ACTION_PREVIOUS -> control(context, intent, "previous")
            NowPlayingWidget.ACTION_CYCLE_DEVICE -> {
                val pending = goAsync()
                Thread {
                    NowPlayingController.cycleDevice(context.applicationContext)
                    pending.finish()
                }.start()
            }
            NowPlayingWidget.ACTION_OPEN -> {
                context.startActivity(
                    MainActivity.launchIntent(context, "#nowplaying").addFlags(FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun control(context: Context, intent: Intent, action: String) {
        val deviceId = intent.getStringExtra(NowPlayingWidget.EXTRA_DEVICE_ID) ?: return
        val deviceName = intent.getStringExtra(NowPlayingWidget.EXTRA_DEVICE_NAME) ?: return
        val pending = goAsync()
        Thread {
            val ok = NowPlayingController.sendControl(context.applicationContext, deviceId, deviceName, action)
            if (!ok) {
                Toast.makeText(context, R.string.widget_control_failed, Toast.LENGTH_SHORT).show()
            }
            pending.finish()
        }.start()
    }
}
