package com.bockmedia.console.data.network

import com.bockmedia.console.data.local.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Picks LAN URL when reachable, otherwise the external static IP. */
object ServerEndpointResolver {
    private var cachedUrl: String? = null
    private var cachedAtMs = 0L
    private const val CACHE_TTL_MS = 30_000L

    fun invalidate() {
        cachedUrl = null
        cachedAtMs = 0L
    }

    suspend fun resolve(
        preferences: AppPreferences,
        localProbeClient: OkHttpClient,
        externalProbeClient: OkHttpClient,
        forceRefresh: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedUrl?.let { url ->
                if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
                    return@withContext url
                }
            }
        }

        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        val probeClient = localProbeClient.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
        val externalClient = externalProbeClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()

        if (!local.isNullOrBlank() && probe(probeClient, local)) {
            return@withContext cache(AppPreferences.normalizeUrl(local))
        }
        if (!external.isNullOrBlank() && probe(externalClient, external)) {
            return@withContext cache(AppPreferences.normalizeUrl(external))
        }
        // Neither endpoint answered — prefer external when away from home; don't cache dead URLs.
        if (!external.isNullOrBlank()) {
            return@withContext AppPreferences.normalizeUrl(external)
        }
        if (!local.isNullOrBlank()) {
            return@withContext AppPreferences.normalizeUrl(local)
        }
        throw IllegalStateException("No server URL configured")
    }

    private fun cache(url: String): String {
        cachedUrl = url
        cachedAtMs = System.currentTimeMillis()
        return url
    }

    private fun probe(client: OkHttpClient, base: String): Boolean {
        val url = "${AppPreferences.normalizeUrl(base)}/api/health"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
