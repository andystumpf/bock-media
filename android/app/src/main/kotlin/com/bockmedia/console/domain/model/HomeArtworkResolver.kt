package com.bockmedia.console.domain.model

import com.bockmedia.console.data.local.AppPreferences
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

    /** Build artwork URL from cached paths only — no network (safe for first paint). */
    fun peekUrl(baseUrl: String?, card: HomeCard): String? {
        val path = HomeArtworkCache.mediaPathFor(card) ?: return null
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return AppPreferences.artworkUrl(base, path)
    }

    suspend fun warmPlaylistCovers(repository: BockMediaRepository, cards: List<HomeCard>) {
        val ids = cards.mapNotNull { it.playlistId }.distinct()
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
    ): String? {
        HomeArtworkCache.mediaPathFor(card)?.let { path ->
            repository.artworkUrl(path)?.let { url ->
                HomeArtworkCache.storeCard(card.id, path)
                return url
            }
        }

        if (!allowNetwork) return null

        card.artPath?.let { path ->
            repository.artworkUrl(path)?.let { url ->
                HomeArtworkCache.storeCard(card.id, path)
                return url
            }
        }

        card.playlistId?.let { playlistId ->
            HomeArtworkCache.playlistPath(playlistId)?.let { path ->
                repository.artworkUrl(path)?.let { url ->
                    HomeArtworkCache.storeCard(card.id, path)
                    return url
                }
            }
        }

        repository.resolveHomeCardArtUrl(card.id, card.artPath, card.playlistId, card.playTarget)?.let { url ->
            val path = ArtworkPaths.extractMediaPath(url)
            if (path != null) HomeArtworkCache.storeCard(card.id, path)
            else HomeArtworkCache.storeCardUrl(card.id, url)
            return url
        }
        return null
    }
}
