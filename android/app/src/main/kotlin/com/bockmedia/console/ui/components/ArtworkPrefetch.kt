package com.bockmedia.console.ui.components

import android.content.Context
import coil.Coil
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
                    val key = stableArtCacheKey(url)
                    loader.execute(
                        ImageRequest.Builder(context)
                            .data(url)
                            .memoryCacheKey(key)
                            .diskCacheKey(key)
                            .build(),
                    )
                }
            }
        }
    }
}
