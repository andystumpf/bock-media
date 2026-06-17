package com.bockmedia.console.domain.model

import android.content.Context

/** Loads disk snapshots into in-memory session caches before the first frame paints. */
object SessionDiskHydrator {
    @Volatile
    private var hydrated = false

    suspend fun hydrate(context: Context) {
        if (hydrated) return
        hydrateHome(context)
        hydrateLibrary(context)
        hydrated = true
    }

    private suspend fun hydrateHome(context: Context) {
        if (HomeFeedCache.peek() != null) return
        HomeCachePersistence.load(context)?.let { snap ->
            HomeArtworkCache.restore(snap.cardMediaPaths, snap.playlistPaths)
            HomeFeedCache.put(snap.feed)
            HomeLoadCoordinator.markLoaded()
        }
    }

    private suspend fun hydrateLibrary(context: Context) {
        if (LibrarySessionCache.peek() != null) return
        LibraryCachePersistence.load(context)?.let { LibrarySessionCache.put(it) }
    }
}
