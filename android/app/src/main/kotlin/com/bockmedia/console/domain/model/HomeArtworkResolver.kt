package com.bockmedia.console.domain.model

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

    suspend fun warmPlaylistCovers(repository: BockMediaRepository, cards: List<HomeCard>) {
        val ids = cards.mapNotNull { it.playlistId }.distinct()
        repository.prefetchPlaylistCoverPaths(ids)
    }

    /** Resolve artwork URLs for every home card and return distinct URLs for Coil prefetch. */
    suspend fun warmAll(repository: BockMediaRepository, cards: List<HomeCard>): List<String> =
        withContext(Dispatchers.IO) {
            if (cards.isEmpty()) return@withContext emptyList()
            warmPlaylistCovers(repository, cards)
            val pending = cards.filter { HomeArtworkCache.urlFor(it.id) == null }
            val sem = Semaphore(RESOLVE_CONCURRENCY)
            coroutineScope {
                pending.map { card ->
                    async {
                        sem.withPermit {
                            runCatching { resolveUrl(repository, card) }
                        }
                    }
                }.awaitAll()
            }
            cards.mapNotNull { HomeArtworkCache.urlFor(it.id) }.distinct()
        }

    suspend fun resolveUrl(repository: BockMediaRepository, card: HomeCard): String? {
        HomeArtworkCache.urlFor(card.id)?.let { return it }

        card.artPath?.let { path ->
            repository.artworkUrl(path)?.let { url ->
                HomeArtworkCache.storeCardUrl(card.id, url)
                return url
            }
        }

        card.playlistId?.let { playlistId ->
            HomeArtworkCache.playlistPath(playlistId)?.let { path ->
                repository.artworkUrl(path)?.let { url ->
                    HomeArtworkCache.storeCardUrl(card.id, url)
                    return url
                }
            }
        }

        repository.resolveHomeCardArtUrl(card.id, card.artPath, card.playlistId, card.playTarget)?.let { url ->
            HomeArtworkCache.storeCardUrl(card.id, url)
            return url
        }
        return null
    }
}
