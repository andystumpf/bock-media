package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AutomationItem

/** In-memory automations list so tab switches don't re-hit the API. */
object AutomationSessionCache {
    private const val TTL_MS = 600_000L

    data class Snapshot(
        val items: List<AutomationItem>,
        val remoteOk: Boolean,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    fun peek(): Snapshot? = snapshot

    fun getIfFresh(): Snapshot? {
        val cached = snapshot ?: return null
        if (System.currentTimeMillis() - cachedAtMs > TTL_MS) return null
        return cached
    }

    fun put(items: List<AutomationItem>, remoteOk: Boolean) {
        snapshot = Snapshot(items, remoteOk)
        cachedAtMs = System.currentTimeMillis()
    }

    fun invalidate() {
        snapshot = null
        cachedAtMs = 0L
    }
}
