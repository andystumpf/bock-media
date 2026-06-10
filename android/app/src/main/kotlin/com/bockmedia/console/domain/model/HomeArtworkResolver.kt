package com.bockmedia.console.domain.model

import com.bockmedia.console.data.repository.BockMediaRepository

object HomeArtworkResolver {
    suspend fun warmPlaylistCovers(repository: BockMediaRepository, cards: List<HomeCard>) {
        val ids = cards.mapNotNull { it.playlistId }.distinct()
        repository.prefetchPlaylistCoverPaths(ids)
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
