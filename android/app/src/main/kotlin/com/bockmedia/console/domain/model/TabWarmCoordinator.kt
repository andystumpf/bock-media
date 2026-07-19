package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LibraryCachePersistence
import com.bockmedia.console.ui.library.LibraryArtPrefetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Deferred tab prefetch — run when a tab is first opened, not on home boot. */
object TabWarmCoordinator {
    fun warmLibrary(scope: CoroutineScope, context: Context, repository: BockMediaRepository) {
        if (LibrarySessionCache.getIfFresh() != null) return
        scope.launch {
            runCatching {
                val data = LibraryLoader.loadBuckets(repository, context)
                LibrarySessionCache.put(data)
                LibraryCachePersistence.save(context, data)
                LibraryArtPrefetch.warm(context, repository, data.forFilter(LibraryFilter.All))
            }
        }
    }

    fun warmSearchBrowse(scope: CoroutineScope, repository: BockMediaRepository) {
        if (SearchBrowseSessionCache.getIfFresh() != null) return
        scope.launch {
            runCatching {
                val fast = SearchBrowseLoader.loadFast(repository)
                SearchBrowseSessionCache.put(fast)
                val full = SearchBrowseLoader.load(repository)
                SearchBrowseSessionCache.put(full)
            }
        }
    }
}
