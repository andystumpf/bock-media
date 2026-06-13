package com.bockmedia.console.domain.model

import java.util.concurrent.ConcurrentHashMap

/** Session cache for home card artwork URLs and playlist cover paths. */
object HomeArtworkCache {
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val cardUrls = ConcurrentHashMap<String, String>()
    private val playlistPaths = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedAtMs: Long = 0L

    private fun fresh(): Boolean =
        cachedAtMs > 0L && System.currentTimeMillis() - cachedAtMs < TTL_MS

    fun urlsFor(cardIds: Collection<String>): Map<String, String> {
        if (!fresh()) return emptyMap()
        return cardIds.mapNotNull { id -> cardUrls[id]?.let { id to it } }.toMap()
    }

    fun playlistPath(id: String): String? = if (fresh()) playlistPaths[id] else null

    fun urlFor(cardId: String): String? = if (fresh()) cardUrls[cardId] else null

    fun storeCardUrl(cardId: String, url: String) {
        touch()
        cardUrls[cardId] = url
    }

    fun storePlaylistPath(id: String, path: String) {
        touch()
        playlistPaths[id] = path
    }

    fun storePlaylistPaths(paths: Map<String, String>) {
        if (paths.isEmpty()) return
        touch()
        playlistPaths.putAll(paths)
    }

    fun merge(urls: Map<String, String>) {
        if (urls.isEmpty()) return
        touch()
        cardUrls.putAll(urls)
    }

    fun invalidate() {
        cardUrls.clear()
        playlistPaths.clear()
        cachedAtMs = 0L
    }

    private fun touch() {
        if (cachedAtMs == 0L) cachedAtMs = System.currentTimeMillis()
    }
}
