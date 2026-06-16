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

    /** True when every card already has a resolved artwork URL in memory. */
    fun isFullyWarmed(cards: Collection<HomeCard>): Boolean =
        cards.isNotEmpty() || cards.all { urlFor(it.id) != null }

    fun urlsForCards(cards: Collection<HomeCard>): List<String> =
        cards.mapNotNull { urlFor(it.id) }.distinct()

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

    /** Current resolved artwork lookups, for disk persistence. */
    fun snapshotCardUrls(): Map<String, String> = HashMap(cardUrls)

    fun snapshotPlaylistPaths(): Map<String, String> = HashMap(playlistPaths)

    /** Seed from a disk snapshot on cold start (does not overwrite fresher data). */
    fun restore(cardUrls: Map<String, String>, playlistPaths: Map<String, String>) {
        if (cardUrls.isEmpty() && playlistPaths.isEmpty()) return
        touch()
        cardUrls.forEach { (k, v) -> this.cardUrls.putIfAbsent(k, v) }
        playlistPaths.forEach { (k, v) -> this.playlistPaths.putIfAbsent(k, v) }
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
