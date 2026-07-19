package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchResponse

/** Short-TTL in-memory cache so backspace/retype and tab switches avoid repeat network calls. */
object SearchQueryCache {
    private const val TTL_MS = 45_000L

    @Volatile private var query: String = ""
    @Volatile private var response: SearchResponse? = null
    @Volatile private var savedAtMs: Long = 0L

    fun get(q: String): SearchResponse? {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return null
        val hit = response ?: return null
        if (this.query != trimmed) return null
        if (System.currentTimeMillis() - savedAtMs > TTL_MS) return null
        return hit
    }

    /** Reuse a shorter cached query while the user extends the string (e.g. learn → learn t). */
    fun getPrefixExtension(q: String): SearchResponse? {
        val trimmed = q.trim()
        if (trimmed.length < 2) return null
        val hit = response ?: return null
        val cached = this.query
        if (cached.length < 2 || trimmed == cached) return null
        if (System.currentTimeMillis() - savedAtMs > TTL_MS) return null
        if (!trimmed.startsWith(cached)) return null
        return hit
    }

    fun put(q: String, response: SearchResponse) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        this.query = trimmed
        this.response = response
        savedAtMs = System.currentTimeMillis()
    }

    fun invalidate() {
        query = ""
        response = null
        savedAtMs = 0L
    }
}
