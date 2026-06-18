package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * Host-independent cache key for artwork. The same media served from the LAN IP
 * and the external IP must share one cache entry, so we key on the path (+query)
 * only. Without this, switching networks re-downloads every image.
 */
fun stableArtCacheKey(model: Any?): String {
    val raw = model?.toString().orEmpty()
    return runCatching {
        val uri = java.net.URI(raw)
        if (uri.host != null) {
            buildString {
                append(uri.rawPath ?: "")
                uri.rawQuery?.let { append('?').append(it) }
            }.ifBlank { raw }
        } else {
            raw
        }
    }.getOrDefault(raw)
}

@Composable
fun BockArtwork(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentScale: ContentScale = ContentScale.Crop,
    fallbackFontSize: TextUnit = 22.sp,
    crossfadeMs: Int = 80,
) {
    val context = LocalContext.current
    val clippedModifier = modifier.clip(shape)
    if (model == null) {
        ArtGradientFallback(title, clippedModifier, fallbackFontSize)
        return
    }
    val request = remember(model, crossfadeMs) {
        val key = stableArtCacheKey(model)
        ImageRequest.Builder(context)
            .data(model)
            // Half-size bitmaps for opaque artwork — big memory/decode win on lists (perf #2).
            .allowRgb565(true)
            .crossfade(crossfadeMs)
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .build()
    }
    // AsyncImage avoids the per-tile subcomposition that makes SubcomposeAsyncImage
    // janky in long lists, and auto-sizes the decode to the composable bounds (perf #2).
    var isError by remember(model) { mutableStateOf(false) }
    Box(clippedModifier) {
        AsyncImage(
            model = request,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onState = { state -> isError = state is AsyncImagePainter.State.Error },
        )
        if (isError) {
            ArtGradientFallback(title, Modifier.fillMaxSize(), fallbackFontSize)
        }
    }
}
