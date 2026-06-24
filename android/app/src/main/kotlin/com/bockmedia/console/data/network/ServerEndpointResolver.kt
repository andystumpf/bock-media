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
    @Volatile private var cachedUrl: String? = null
    @Volatile private var cachedAtMs = 0L
    private const val CACHE_TTL_MS = 60_000L
    private val resolveMutex = Mutex()

    // Health checks hit a tiny JSON endpoint; keep probes short so a hung/old
    // server fails fast and we fall back instead of blocking startup for ~15s.
    private const val LAN_CONNECT_SEC = 2L
    private const val LAN_READ_SEC = 3L
    private const val EXT_CONNECT_SEC = 3L
    private const val EXT_READ_SEC = 5L

    fun invalidate() {
        cachedUrl = null
        cachedAtMs = 0L
    }

    /**
     * Seed the in-memory cache with the last-good endpoint from disk so the first
     * API call after launch returns instantly instead of waiting on probes. The
     * normal TTL still forces a re-probe shortly after, correcting stale values.
     */
    fun prime(url: String?) {
        if (url.isNullOrBlank()) return
        if (cachedUrl == null) {
            cachedUrl = AppPreferences.normalizeUrl(url)
            cachedAtMs = System.currentTimeMillis()
        }
    }

    /** Testable selection when probes finish. Prefer LAN whenever it answers. */
    internal fun pickEndpoint(
        local: String?,
        external: String?,
        localReachable: Boolean,
        externalReachable: Boolean,
        wifiAvailable: Boolean = true,
    ): String? {
        if (!wifiAvailable) {
            // On cellular the LAN host is unreachable — never fall back to it.
            return external?.takeIf { it.isNotBlank() }?.let { AppPreferences.normalizeUrl(it) }
        }
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
        wifiAvailable: Boolean = true,
    ): String = resolveMutex.withLock {
        withContext(Dispatchers.IO) {
            val local = preferences.getLocalServerUrlSync()
            val external = preferences.getExternalServerUrlSync()

            if (!forceRefresh) {
                cachedUrl?.let { url ->
                    if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
                        if (wifiAvailable || !AppPreferences.isLanHost(url, local, external)) {
                            return@withContext url
                        }
                    }
                }
            }

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
                    wifiAvailable && !local.isNullOrBlank() && probe(localClient, local)
                }
                val externalProbe = async {
                    !external.isNullOrBlank() && probe(externalClient, external)
                }
                localProbe.await() to externalProbe.await()
            }

            val chosen = pickEndpoint(local, external, localOk, externalOk, wifiAvailable)
                ?: throw IllegalStateException("No server URL configured")
            if (localOk || externalOk) {
                runCatching { preferences.setLastGoodEndpoint(chosen) }
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
