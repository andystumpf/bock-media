package com.bockmedia.console.domain.model

import java.util.concurrent.ConcurrentHashMap

/** Session cache for home card artwork paths and playlist cover paths. */
object HomeArtworkCache {
    private val cardMediaPaths = ConcurrentHashMap<String, String>()
    private val playlistPaths = ConcurrentHashMap<String, String>()

    @Volatile
    private var cachedAtMs: Long = 0L

    private fun fresh(): Boolean = cachedAtMs > 0L

    /** True when every card has a known media path (no per-card API lookup needed). */
    fun isFullyWarmed(cards: Collection<HomeCard>): Boolean =
        cards.isEmpty() || cards.all { mediaPathFor(it) != null }

    fun mediaPathFor(card: HomeCard): String? {
        if (!fresh()) return null
        card.artPath?.takeIf { it.isNotBlank() }?.let { return it }
        cardMediaPaths[card.id]?.let { return it }
        card.playlistId?.let { playlistPaths[it] }?.let { return it }
        return null
    }

    fun pathFor(cardId: String): String? = if (fresh()) cardMediaPaths[cardId] else null

    fun playlistPath(id: String): String? = if (fresh()) playlistPaths[id] else null

    /** @deprecated Use mediaPathFor + current base URL. Kept for migration reads. */
    fun urlFor(cardId: String): String? = pathFor(cardId)

    fun storeCard(cardId: String, mediaPath: String, url: String? = null) {
        if (mediaPath.isBlank()) return
        touch()
        cardMediaPaths[cardId] = mediaPath
        url?.let { /* legacy no-op; paths are authoritative */ }
    }

    fun storeCardUrl(cardId: String, url: String) {
        ArtworkPaths.extractMediaPath(url)?.let { storeCard(cardId, it) }
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

    fun snapshotCardPaths(): Map<String, String> = HashMap(cardMediaPaths)

    fun snapshotPlaylistPaths(): Map<String, String> = HashMap(playlistPaths)

    /** @deprecated */ fun snapshotCardUrls(): Map<String, String> = snapshotCardPaths()

    fun restore(cardPaths: Map<String, String>, playlistPaths: Map<String, String>) {
        if (cardPaths.isEmpty() && playlistPaths.isEmpty()) return
        touch()
        cardPaths.forEach { (k, v) ->
            val path = ArtworkPaths.extractMediaPath(v) ?: v
            if (path.isNotBlank()) this.cardMediaPaths.putIfAbsent(k, path)
        }
        playlistPaths.forEach { (k, v) -> this.playlistPaths.putIfAbsent(k, v) }
    }

    /** Migrate legacy snapshots that stored full artwork URLs. */
    fun restoreLegacy(cardUrls: Map<String, String>, playlistPaths: Map<String, String>) {
        restore(
            cardUrls.mapNotNull { (id, url) ->
                ArtworkPaths.extractMediaPath(url)?.let { id to it }
            }.toMap(),
            playlistPaths,
        )
    }

    fun invalidate() {
        cardMediaPaths.clear()
        playlistPaths.clear()
        cachedAtMs = 0L
    }

    private fun touch() {
        if (cachedAtMs == 0L) cachedAtMs = System.currentTimeMillis()
    }
}
