package com.bockmedia.console.domain.model

import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.ArtworkPaths.TILE_SIZE_PX
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

object HomeArtworkResolver {
    private const val RESOLVE_CONCURRENCY = 8
    private const val MAX_COVER_WARM = 48
    private const val MAX_WARM_SECTIONS = 4
    private const val MAX_WARM_CARDS_PER_SECTION = 12

    /** Visible home tiles only — first N sections × M cards. */
    fun visibleCardsForWarm(
        feed: HomeFeed,
        maxSections: Int = MAX_WARM_SECTIONS,
        maxCardsPerSection: Int = MAX_WARM_CARDS_PER_SECTION,
    ): List<HomeCard> =
        feed.sections.take(maxSections).flatMap { it.cards.take(maxCardsPerSection) }

    /** Build artwork URL from cached paths only — no network (safe for first paint). */
    fun peekUrl(baseUrl: String?, card: HomeCard, sizePx: Int = TILE_SIZE_PX): String? {
        val path = HomeArtworkCache.mediaPathFor(card) ?: return null
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return AppPreferences.artworkUrl(base, path, sizePx)
    }

    suspend fun warmPlaylistCovers(repository: BockMediaRepository, cards: List<HomeCard>) {
        val ids = cards
            .filter { it.artPath.isNullOrBlank() }
            .mapNotNull { it.linkedPlaylistId() }
            .filter { HomeArtworkCache.playlistPath(it) == null }
            .distinct()
            .take(MAX_COVER_WARM)
        if (ids.isEmpty()) return
        repository.prefetchPlaylistCoverPaths(ids)
    }

    suspend fun warmAll(repository: BockMediaRepository, cards: List<HomeCard>): List<String> =
        withContext(Dispatchers.IO) {
            if (cards.isEmpty()) return@withContext emptyList()
            warmPlaylistCovers(repository, cards)
            val pending = cards.filter { resolveUrl(repository, card = it, allowNetwork = false) == null }
            val sem = Semaphore(RESOLVE_CONCURRENCY)
            coroutineScope {
                pending.map { card ->
                    async {
                        sem.withPermit {
                            runCatching { resolveUrl(repository, card, allowNetwork = true) }
                        }
                    }
                }.awaitAll()
            }
            cards.mapNotNull { resolveUrl(repository, it, allowNetwork = false) }.distinct()
        }

    suspend fun resolveUrl(
        repository: BockMediaRepository,
        card: HomeCard,
        allowNetwork: Boolean = true,
        sizePx: Int = TILE_SIZE_PX,
    ): String? {
        HomeArtworkCache.mediaPathFor(card)?.let { path ->
            repository.artworkUrl(path, sizePx)?.let { url ->
                HomeArtworkCache.storeCard(card.id, path)
                return url
            }
        }

        val linkedPlaylistId = card.linkedPlaylistId()

        linkedPlaylistId?.let { playlistId ->
            HomeArtworkCache.playlistPath(playlistId)?.let { path ->
                repository.artworkUrl(path, sizePx)?.let { url ->
                    HomeArtworkCache.storeCard(card.id, path)
                    return url
                }
            }
        }

        if (!allowNetwork) return null

        linkedPlaylistId?.let { playlistId ->
            repository.artworkUrlForPlaylist(playlistId, card.id, sizePx)?.let { url ->
                val path = ArtworkPaths.extractMediaPath(url)
                if (path != null) HomeArtworkCache.storeCard(card.id, path)
                else HomeArtworkCache.storeCardUrl(card.id, url)
                return url
            }
        }

        card.artPath?.takeIf { linkedPlaylistId == null }?.let { path ->
            repository.artworkUrl(path, sizePx)?.let { url ->
                HomeArtworkCache.storeCard(card.id, path)
                return url
            }
        }

        repository.resolveHomeCardArtUrl(card.id, card.artPath, card.playlistId, card.playTarget, sizePx)?.let { url ->
            val path = ArtworkPaths.extractMediaPath(url)
            if (path != null) HomeArtworkCache.storeCard(card.id, path)
            else HomeArtworkCache.storeCardUrl(card.id, url)
            return url
        }
        return null
    }
}
