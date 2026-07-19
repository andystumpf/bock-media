package com.bockmedia.console.ui.search

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.SearchSuggestionKind
import com.bockmedia.console.ui.components.ArtworkPrefetch

/** Batch-resolve search result artwork instead of per-row LaunchedEffect storms. */
internal object SearchArtPrefetch {
    private const val MAX_ITEMS = 12

    suspend fun warm(
        context: Context,
        repository: BockMediaRepository,
        items: List<SpotifyFlatItem>,
    ) {
        if (items.isEmpty()) return
        val urls = items.take(MAX_ITEMS).mapNotNull { item ->
            val (kind, hit, variantKey) = when (item) {
                is SpotifyFlatItem.Song -> Triple(
                    SearchSuggestionKind.Song,
                    item.hit,
                    item.hit.path ?: item.hit.title.orEmpty(),
                )
                is SpotifyFlatItem.Playlist -> Triple(
                    SearchSuggestionKind.Playlist,
                    item.hit,
                    item.hit.id ?: item.hit.name.orEmpty(),
                )
                is SpotifyFlatItem.SmartPlaylist -> Triple(
                    SearchSuggestionKind.Playlist,
                    item.hit,
                    item.hit.id ?: item.hit.name.orEmpty(),
                )
                is SpotifyFlatItem.Artist -> Triple(
                    SearchSuggestionKind.Artist,
                    item.hit,
                    item.hit.name.orEmpty(),
                )
                is SpotifyFlatItem.Album -> Triple(
                    SearchSuggestionKind.Album,
                    item.hit,
                    item.hit.id ?: item.hit.name.orEmpty(),
                )
                is SpotifyFlatItem.Genre -> Triple(
                    SearchSuggestionKind.Album,
                    item.hit,
                    item.hit.name.orEmpty(),
                )
            }
            runCatching { repository.resolveSearchHitArtUrl(kind, hit, variantKey) }.getOrNull()
        }.distinct()
        if (urls.isNotEmpty()) ArtworkPrefetch.prefetchUrls(context, urls)
    }
}
