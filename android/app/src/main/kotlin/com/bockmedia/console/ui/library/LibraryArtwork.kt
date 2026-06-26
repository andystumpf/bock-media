package com.bockmedia.console.ui.library

import android.content.Context
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtworkPaths.TILE_SIZE_PX
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.LibraryItem
import com.bockmedia.console.domain.model.LibraryItemKind
import com.bockmedia.console.ui.components.ArtworkPrefetch

/** Build artwork URL from cached paths only — no network (first-frame paint). */
fun peekLibraryArtUrl(baseUrl: String?, item: LibraryItem, sizePx: Int = TILE_SIZE_PX): String? {
    val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    item.artPath?.takeIf { it.isNotBlank() }?.let { return AppPreferences.artworkUrl(base, it, sizePx) }
    item.playlistId?.let { HomeArtworkCache.playlistPath(it) }?.let {
        return AppPreferences.artworkUrl(base, it, sizePx)
    }
    return null
}

object LibraryArtPrefetch {
    private const val MAX_PLAYLIST_PREFETCH = 64
    private const val MAX_ART_URL_PREFETCH = 24

    suspend fun warm(context: Context, repository: BockMediaRepository, items: List<LibraryItem>) {
        if (items.isEmpty()) return
        val playlistIds = items.filter { it.kind == LibraryItemKind.Playlist }
            .mapNotNull { it.playlistId }
            .distinct()
            .filter { HomeArtworkCache.playlistPath(it) == null }
            .take(MAX_PLAYLIST_PREFETCH)
        if (playlistIds.isNotEmpty()) {
            runCatching { repository.prefetchPlaylistCoverPaths(playlistIds) }
        }
        val base = repository.peekBaseUrl()
            ?: runCatching { repository.testConnection(); repository.peekBaseUrl() }.getOrNull()
            ?: return
        val urls = items.mapNotNull { peekLibraryArtUrl(base, it) }.distinct().take(MAX_ART_URL_PREFETCH)
        if (urls.isNotEmpty()) ArtworkPrefetch.prefetchUrls(context, urls)
    }
}
