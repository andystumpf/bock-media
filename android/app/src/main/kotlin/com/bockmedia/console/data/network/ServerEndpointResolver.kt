package com.bockmedia.console.data.network

import com.bockmedia.console.data.local.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Picks LAN URL when reachable, otherwise the external static IP. */
object ServerEndpointResolver {
    private var cachedUrl: String? = null
    private var cachedAtMs = 0L
    private const val CACHE_TTL_MS = 60_000L
    private val resolveMutex = Mutex()

    private const val LAN_CONNECT_SEC = 5L
    private const val LAN_READ_SEC = 8L
    private const val EXT_CONNECT_SEC = 5L
    private const val EXT_READ_SEC = 10L

    fun invalidate() {
        cachedUrl = null
        cachedAtMs = 0L
    }

    /** Testable selection when probes finish. Prefer LAN whenever it answers. */
    internal fun pickEndpoint(
        local: String?,
        external: String?,
        localReachable: Boolean,
        externalReachable: Boolean,
    ): String? {
        if (!local.isNullOrBlank() && localReachable) {
            return AppPreferences.normalizeUrl(local)
        }
        if (!external.isNullOrBlank() && externalReachable) {
            return AppPreferences.normalizeUrl(external)
        }
        // Neither answered — default to LAN at home (avoids hairpin-NAT / dead external IP).
        if (!local.isNullOrBlank()) {
            return AppPreferences.normalizeUrl(local)
        }
        if (!external.isNullOrBlank()) {
            return AppPreferences.normalizeUrl(external)
        }
        return null
    }

    suspend fun resolve(
        preferences: AppPreferences,
        probeClient: OkHttpClient,
        forceRefresh: Boolean = false,
    ): String = resolveMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                cachedUrl?.let { url ->
                    if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
                        return@withContext url
                    }
                }
            }

            val local = preferences.getLocalServerUrlSync()
            val external = preferences.getExternalServerUrlSync()
            val localClient = probeClient.newBuilder()
                .connectTimeout(LAN_CONNECT_SEC, TimeUnit.SECONDS)
                .readTimeout(LAN_READ_SEC, TimeUnit.SECONDS)
                .callTimeout(LAN_CONNECT_SEC + LAN_READ_SEC, TimeUnit.SECONDS)
                .build()
            val externalClient = probeClient.newBuilder()
                .connectTimeout(EXT_CONNECT_SEC, TimeUnit.SECONDS)
                .readTimeout(EXT_READ_SEC, TimeUnit.SECONDS)
                .callTimeout(EXT_CONNECT_SEC + EXT_READ_SEC, TimeUnit.SECONDS)
                .build()

            val (localOk, externalOk) = coroutineScope {
                val localProbe = async {
                    !local.isNullOrBlank() && probe(localClient, local)
                }
                val externalProbe = async {
                    !external.isNullOrBlank() && probe(externalClient, external)
                }
                localProbe.await() to externalProbe.await()
            }

            val chosen = pickEndpoint(local, external, localOk, externalOk)
                ?: throw IllegalStateException("No server URL configured")
            if (localOk || externalOk) {
                cache(chosen)
            } else {
                chosen
            }
        }
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
