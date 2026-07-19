package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.ui.components.ArtworkPrefetch

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

    /** Decode cached visible tiles into Coil memory — background only, capped. */
    suspend fun warmHomeArtwork(context: Context, app: BockMediaApp, maxUrls: Int = 32) {
        NetworkReachability.update(context)
        app.configuredEndpointUrl()?.let { app.repository.primeBaseUrl(it) }
        val feed = HomeFeedCache.peek() ?: return
        val base = app.repository.peekBaseUrl() ?: return
        val urls = feed.sections.flatMap { it.cards }
            .mapNotNull { HomeArtworkResolver.peekUrl(base, it) }
            .distinct()
            .take(maxUrls)
        if (urls.isNotEmpty()) {
            ArtworkPrefetch.prefetchUrls(context, urls)
        }
    }

    private suspend fun hydrateHome(context: Context) {
        if (HomeFeedCache.peek() != null) return
        val linked = !ActiveProfileStore.activeMemberId(context).isNullOrBlank()
        HomeCachePersistence.load(context)?.let { snap ->
            if (!snap.feed.isUsableHomeCache(linked, snap.hasRatedSongs)) return@let
            HomeArtworkCache.restore(snap.cardMediaPaths, snap.playlistPaths)
            HomeFeedCache.put(snap.feed, hasRatedSongs = snap.hasRatedSongs)
            HomeLoadCoordinator.markLoaded()
        }
    }

    private suspend fun hydrateLibrary(context: Context) {
        if (LibrarySessionCache.peek() != null) return
        LibraryCachePersistence.load(context)?.let { LibrarySessionCache.put(it) }
    }
}
