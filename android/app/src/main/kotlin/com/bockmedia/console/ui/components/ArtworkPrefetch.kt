package com.bockmedia.console.ui.components

import android.content.Context
import coil.Coil
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Prefetch artwork bytes into Coil memory/disk cache so home tiles paint without scrolling. */
object ArtworkPrefetch {
    suspend fun prefetchUrls(context: Context, urls: Collection<String>) {
        val distinct = urls.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        withContext(Dispatchers.IO) {
            val loader = Coil.imageLoader(context)
            distinct.forEach { url ->
                runCatching {
                    val keyStr = stableArtCacheKey(url)
                    val memKey = MemoryCache.Key(keyStr)
                    if (loader.memoryCache?.get(memKey) != null) return@forEach
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
    }
}
