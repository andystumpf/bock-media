package com.bockmedia.console.data.network

import com.bockmedia.console.data.local.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Picks the reachable server URL (LAN or external). Caches aggressively for mobile. */
object ServerEndpointResolver {
    private var cachedUrl: String? = null
    private var cachedAtMs = 0L
    private const val CACHE_TTL_MS = 300_000L // 5 min — avoid re-probing on every screen

    fun invalidate() {
        cachedUrl = null
        cachedAtMs = 0L
    }

    suspend fun resolve(
        preferences: AppPreferences,
        authProbeClient: OkHttpClient,
        preferExternal: Boolean = false,
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
        val probeClient = authProbeClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        // Last-known-good URL (persisted) — one fast probe before scanning.
        preferences.getLastResolvedUrlSync()?.let { last ->
            if (probe(probeClient, last)) {
                preferences.setLastResolvedUrl(last)
                return@withContext cache(AppPreferences.normalizeUrl(last))
            }
        }

        val candidates = buildList {
            if (preferExternal) {
                external?.takeIf { it.isNotBlank() }?.let { add(it) }
                local?.takeIf { it.isNotBlank() }?.let { add(it) }
            } else {
                local?.takeIf { it.isNotBlank() }?.let { add(it) }
                external?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.distinct()

        if (candidates.isEmpty()) {
            throw IllegalStateException("No reachable Bock Media server URL")
        }

        // Probe all candidates in parallel — total wait ≈ one timeout, not N×.
        val winner = coroutineScope {
            candidates.map { base ->
                async {
                    val norm = AppPreferences.normalizeUrl(base)
                    if (probe(probeClient, norm)) norm else null
                }
            }.mapNotNull { it.await() }.firstOrNull()
        }

        if (winner != null) {
            preferences.setLastResolvedUrl(winner)
            return@withContext cache(winner)
        }
        if (candidates.any { probeAuthFailed(probeClient, AppPreferences.normalizeUrl(it)) }) {
            throw IllegalStateException(
                "Server reachable but authentication failed — check Mobile API token in Settings",
            )
        }
        throw IllegalStateException("No reachable Bock Media server URL")
    }

    private fun probeAuthFailed(client: OkHttpClient, base: String): Boolean {
        val url = "${AppPreferences.normalizeUrl(base)}/api/health"
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { it.code == 401 }
        } catch (_: Exception) {
            false
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
