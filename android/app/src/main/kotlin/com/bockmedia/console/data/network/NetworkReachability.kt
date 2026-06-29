package com.bockmedia.console.data.network

import android.content.Context
import com.bockmedia.console.local.OfflineDownloadNetwork

/** Tracks Wi‑Fi vs cellular so API/artwork URLs don't stick on an unreachable LAN host. */
object NetworkReachability {
    @Volatile
    var onWifi: Boolean = true
        private set

    /** Bumped when transport changes — Compose artwork hooks can key off this. */
    @Volatile
    var generation: Int = 0
        private set

    fun update(context: Context) {
        val wifi = OfflineDownloadNetwork.isWifi(context.applicationContext)
        if (wifi != onWifi) {
            onWifi = wifi
            generation++
        }
    }
}
