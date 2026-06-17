package com.bockmedia.console.domain.model

/** In-memory library buckets — filter switches are instant; refreshed in background. */
object LibrarySessionCache {
    private const val TTL_MS = 600_000L

    @Volatile
    var data: LibraryData? = null
        private set

    @Volatile
    private var cachedAtMs: Long = 0L

    fun getIfFresh(): LibraryData? {
        val cached = data ?: return null
        if (System.currentTimeMillis() - cachedAtMs > TTL_MS) return null
        return cached
    }

    fun peek(): LibraryData? = data

    fun put(value: LibraryData) {
        if (value.playlists.isEmpty() && value.artists.isEmpty() && value.albums.isEmpty()) return
        data = value
        cachedAtMs = System.currentTimeMillis()
    }

    fun invalidate() {
        data = null
        cachedAtMs = 0L
    }
}
