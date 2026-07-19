package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.ArtistDetailResponse

/** In-memory artist detail cache — avoids blank reload when revisiting within TTL. */
object ArtistDetailSessionCache {
    data class UiState(
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val collapsedSections: Set<String> = emptySet(),
        val selectedTab: String = "music",
    )

    private data class Entry(
        val detail: ArtistDetailResponse,
        val portraitPath: String?,
        val savedAtMs: Long,
        val ui: UiState = UiState(),
    )

    private const val TTL_MS = 8 * 60 * 1000L
    private val store = mutableMapOf<String, Entry>()

    private fun key(name: String) = name.trim().lowercase()

    fun get(name: String): Pair<ArtistDetailResponse, String?>? {
        val entry = store[key(name)] ?: return null
        if (System.currentTimeMillis() - entry.savedAtMs > TTL_MS) {
            store.remove(key(name))
            return null
        }
        return entry.detail to entry.portraitPath
    }

    fun getUiState(name: String): UiState? {
        val entry = store[key(name)] ?: return null
        if (System.currentTimeMillis() - entry.savedAtMs > TTL_MS) return null
        return entry.ui
    }

    fun put(name: String, detail: ArtistDetailResponse, portraitPath: String?, ui: UiState? = null) {
        val k = key(name)
        if (k.isEmpty()) return
        val prev = store[k]
        store[k] = Entry(
            detail = detail,
            portraitPath = portraitPath,
            savedAtMs = System.currentTimeMillis(),
            ui = ui ?: prev?.ui ?: UiState(),
        )
    }

    fun saveUiState(name: String, ui: UiState) {
        val k = key(name)
        val entry = store[k] ?: return
        store[k] = entry.copy(ui = ui, savedAtMs = System.currentTimeMillis())
    }

    fun prefetch(name: String) = key(name).takeIf { it.isNotEmpty() && !store.containsKey(it) }

    fun clear() = store.clear()
}
