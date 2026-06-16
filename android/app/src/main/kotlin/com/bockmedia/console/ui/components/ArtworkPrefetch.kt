package com.bockmedia.console.ui.components

import android.content.Context
import coil.Coil
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Prefetch artwork bytes into Coil memory/disk cache so home tiles paint without scrolling. */
object ArtworkPrefetch {
    private const val MAX_CONCURRENT = 10

    suspend fun prefetchUrls(context: Context, urls: Collection<String>) {
        val distinct = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        withContext(Dispatchers.IO) {
            val loader = Coil.imageLoader(context)
            val sem = Semaphore(MAX_CONCURRENT)
            coroutineScope {
                distinct.map { url ->
                    async {
                        sem.withPermit {
                            runCatching {
                                val keyStr = stableArtCacheKey(url)
                                val memKey = MemoryCache.Key(keyStr)
                                if (loader.memoryCache?.get(memKey) != null) return@withPermit
                                if (loader.diskCache?.openSnapshot(keyStr) != null) {
                                    loader.execute(
                                        ImageRequest.Builder(context)
                                            .data(url)
                                            .memoryCacheKey(keyStr)
                                            .diskCacheKey(keyStr)
                                            .build(),
                                    )
                                    return@withPermit
                                }
                                loader.execute(
                                    ImageRequest.Builder(context)
                                        .data(url)
                                        .memoryCacheKey(keyStr)
                                        .diskCacheKey(keyStr)
                                        .build(),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
