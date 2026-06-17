package com.bockmedia.console.domain.model

/** In-memory search browse feed so the Search tab paints instantly on revisit. */
object SearchBrowseSessionCache {
    private const val TTL_MS = 600_000L

    @Volatile
    private var feed: SearchBrowseFeed? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    fun peek(): SearchBrowseFeed? = feed

    fun getIfFresh(): SearchBrowseFeed? {
        val cached = feed ?: return null
        if (System.currentTimeMillis() - cachedAtMs > TTL_MS) return null
        return cached
    }

    fun put(value: SearchBrowseFeed) {
        if (value.pickedForYou.isEmpty() && value.newReleases.isEmpty() && value.genres.isEmpty()) return
        feed = value
        cachedAtMs = System.currentTimeMillis()
    }

    fun invalidate() {
        feed = null
        cachedAtMs = 0L
    }
}
