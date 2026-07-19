package com.bockmedia.console.ui.nowplaying

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.data.repository.BockMediaRepository

/** Cellular-safe server base + transport flags for music-video API calls. */
object MusicVideoNetwork {
    fun refreshTransport(context: Context) {
        NetworkReachability.update(context.applicationContext)
    }

    val onCellular: Boolean
        get() = !NetworkReachability.onWifi

    suspend fun resolveServerBase(
        context: Context,
        app: BockMediaApp,
        repository: BockMediaRepository,
        forceRefresh: Boolean = false,
    ): String? {
        refreshTransport(context)
        val prefs = app.preferences
        val local = prefs.getLocalServerUrlSync()
        val external = prefs.getExternalServerUrlSync()?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_EXTERNAL_SERVER_URL.takeIf { it.isNotBlank() }
        var base = runCatching {
            app.resolveBaseUrl(forceRefresh = forceRefresh)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: repository.peekBaseUrl()?.takeIf { it.isNotBlank() }
            ?: return null
        if (onCellular && AppPreferences.isLanHost(base, local, external)) {
            val ext = external ?: return null
            base = AppPreferences.normalizeUrl(ext)
            repository.primeBaseUrl(base)
            app.invalidateEndpoint()
        }
        return base
    }
}
