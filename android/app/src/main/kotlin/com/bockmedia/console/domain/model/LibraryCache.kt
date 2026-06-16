package com.bockmedia.console.domain.model

import java.util.concurrent.ConcurrentHashMap

/** In-memory library cache keyed by filter+search so reopening the tab feels instant. */
object LibraryCache {
    private const val TTL_MS = 60_000L

    private data class Entry(val items: List<LibraryItem>, val atMs: Long)

    private val store = ConcurrentHashMap<String, Entry>()

    private fun key(filter: LibraryFilter, search: String) =
        "${filter.name}|${search.trim().lowercase()}"

    /** Returns cached items regardless of age (for instant paint while refreshing). */
    fun peek(filter: LibraryFilter, search: String): List<LibraryItem>? =
        store[key(filter, search)]?.items

    /** Returns cached items only if still within TTL. */
    fun getIfFresh(filter: LibraryFilter, search: String): List<LibraryItem>? {
        val entry = store[key(filter, search)] ?: return null
        if (System.currentTimeMillis() - entry.atMs > TTL_MS) return null
        return entry.items
    }

    fun put(filter: LibraryFilter, search: String, items: List<LibraryItem>) {
        store[key(filter, search)] = Entry(items, System.currentTimeMillis())
    }

    fun invalidate() = store.clear()
}
