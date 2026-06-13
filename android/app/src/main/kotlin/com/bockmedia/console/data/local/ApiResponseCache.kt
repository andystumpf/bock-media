package com.bockmedia.console.data.local

import android.content.Context
import com.bockmedia.console.data.api.bockJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Disk cache for API JSON — stale-while-revalidate on slow/external links. */
class ApiResponseCache(context: Context) {
    private val dir = File(context.cacheDir, "api_cache").also { it.mkdirs() }

    @Serializable
    private data class Envelope(val savedAtMs: Long, val body: String)

    fun read(key: String, maxAgeMs: Long): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching {
            val env = bockJson.decodeFromString<Envelope>(file.readText())
            if (System.currentTimeMillis() - env.savedAtMs > maxAgeMs) return null
            env.body
        }.getOrNull()
    }

    /** Returns cached body even when stale — for instant paint while refreshing. */
    fun readStale(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return runCatching { bockJson.decodeFromString<Envelope>(file.readText()).body }.getOrNull()
    }

    fun write(key: String, body: String) {
        runCatching {
            fileFor(key).writeText(
                bockJson.encodeToString(Envelope(System.currentTimeMillis(), body)),
            )
        }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String) = File(dir, key.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".json")

    companion object {
        const val TTL_DASHBOARD_MS = 45_000L
        const val TTL_PLAYLISTS_MS = 120_000L
        const val TTL_NOW_PLAYING_MS = 8_000L
        const val TTL_LIBRARY_MS = 300_000L
    }
}
