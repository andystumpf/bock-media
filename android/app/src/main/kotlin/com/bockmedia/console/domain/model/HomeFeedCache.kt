package com.bockmedia.console.domain.model

/** In-memory home feed cache so tab switches and pull-refresh feel instant. */
object HomeFeedCache {
    private const val TTL_MS = 600_000L // 10 min — tab switches stay instant

    @Volatile
    var feed: HomeFeed? = null
        private set

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var hasRatedSongs: Boolean? = null

    /** Last cached feed regardless of age — for instant tab revisit paint. */
    fun peek(): HomeFeed? = feed

    fun peekHasRatedSongs(): Boolean? = hasRatedSongs

    fun getIfFresh(): HomeFeed? {
        val cached = feed ?: return null
        if (System.currentTimeMillis() - cachedAtMs > TTL_MS) return null
        return cached
    }

    fun put(value: HomeFeed, hasRatedSongs: Boolean? = null) {
        if (value.sections.isEmpty()) return
        feed = value
        cachedAtMs = System.currentTimeMillis()
        if (hasRatedSongs != null) this.hasRatedSongs = hasRatedSongs
    }

    fun setHasRatedSongs(value: Boolean) {
        hasRatedSongs = value
    }

    fun invalidate() {
        feed = null
        cachedAtMs = 0L
        hasRatedSongs = null
    }
}
