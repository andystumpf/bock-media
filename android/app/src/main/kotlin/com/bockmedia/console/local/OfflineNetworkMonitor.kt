package com.bockmedia.console.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.network.NetworkReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object OfflineNetworkMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var registered = false
    private var appContext: Context? = null

    fun start(context: Context) {
        if (registered) return
        val ctx = context.applicationContext
        appContext = ctx
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onTransportChanged(ctx)
                }

                override fun onLost(network: Network) {
                    onTransportChanged(ctx)
                }
            },
        )
        registered = true
        onTransportChanged(ctx)
    }

    private fun onTransportChanged(ctx: Context) {
        val wasWifi = NetworkReachability.onWifi
        NetworkReachability.update(ctx)
        if (wasWifi && !NetworkReachability.onWifi) {
            BockMediaApp.get(ctx).onCellularNetwork()
        }
        scope.launch(Dispatchers.IO) {
            OfflineDownloadManager.onNetworkAvailable(ctx)
            if (NetworkReachability.onWifi) {
                OfflineSyncWorker.enqueue(ctx)
            }
        }
    }

    private fun isOnline(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
