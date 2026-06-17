package com.bockmedia.console.ui.library

import android.content.Context
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.LibraryItem
import com.bockmedia.console.domain.model.LibraryItemKind
import com.bockmedia.console.ui.components.ArtworkPrefetch

/** Build artwork URL from cached paths only — no network (first-frame paint). */
fun peekLibraryArtUrl(baseUrl: String?, item: LibraryItem): String? {
    val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    item.artPath?.takeIf { it.isNotBlank() }?.let { return AppPreferences.artworkUrl(base, it) }
    item.playlistId?.let { HomeArtworkCache.playlistPath(it) }?.let {
        return AppPreferences.artworkUrl(base, it)
    }
    return null
}

object LibraryArtPrefetch {
    suspend fun warm(context: Context, repository: BockMediaRepository, items: List<LibraryItem>) {
        if (items.isEmpty()) return
        val playlistIds = items.filter { it.kind == LibraryItemKind.Playlist }
            .mapNotNull { it.playlistId }
            .distinct()
        runCatching { repository.prefetchPlaylistCoverPaths(playlistIds) }
        val base = repository.peekBaseUrl()
            ?: runCatching { repository.testConnection(); repository.peekBaseUrl() }.getOrNull()
            ?: return
        val urls = items.mapNotNull { peekLibraryArtUrl(base, it) }.distinct()
        if (urls.isNotEmpty()) ArtworkPrefetch.prefetchUrls(context, urls)
    }
}
