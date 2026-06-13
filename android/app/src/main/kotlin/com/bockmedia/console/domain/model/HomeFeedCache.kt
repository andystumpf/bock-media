package com.bockmedia.console.domain.model

/** In-memory home feed cache so tab switches and pull-refresh feel instant. */
object HomeFeedCache {
    private const val TTL_MS = 90_000L

    @Volatile
    var feed: HomeFeed? = null
        private set

    @Volatile
    private var cachedAtMs: Long = 0L

    fun getIfFresh(): HomeFeed? {
        val cached = feed ?: return null
        if (System.currentTimeMillis() - cachedAtMs > TTL_MS) return null
        return cached
    }

    fun put(value: HomeFeed) {
        if (value.sections.isEmpty()) return
        feed = value
        cachedAtMs = System.currentTimeMillis()
    }

    fun invalidate() {
        feed = null
        cachedAtMs = 0L
    }
}
