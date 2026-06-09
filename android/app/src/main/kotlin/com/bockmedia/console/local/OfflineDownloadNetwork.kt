package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.BockMediaApp

object OfflineDownloadNetwork {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    suspend fun canDownloadNow(context: Context): Boolean {
        if (!isOnline(context)) return false
        val wifiOnly = BockMediaApp.get(context).preferences.isDownloadWifiOnlySync()
        if (wifiOnly && !isWifi(context)) return false
        return true
    }

    suspend fun blockedReason(context: Context): String? {
        if (!isOnline(context)) return "No network connection"
        val wifiOnly = BockMediaApp.get(context).preferences.isDownloadWifiOnlySync()
        if (wifiOnly && !isWifi(context)) return "Wi‑Fi only — connect to Wi‑Fi to download"
        return null
    }
}
