package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

@Composable
fun BockArtwork(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    contentScale: ContentScale = ContentScale.Crop,
    fallbackFontSize: TextUnit = 22.sp,
) {
    val context = LocalContext.current
    val clippedModifier = modifier.clip(shape)
    if (model == null) {
        ArtGradientFallback(title, clippedModifier, fallbackFontSize)
        return
    }
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(80)
            .memoryCacheKey(model.toString())
            .diskCacheKey(model.toString())
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = title,
        modifier = clippedModifier,
        contentScale = contentScale,
        loading = {
            ArtGradientFallback(title, Modifier.fillMaxSize(), fallbackFontSize)
        },
        error = {
            ArtGradientFallback(title, Modifier.fillMaxSize(), fallbackFontSize)
        },
        success = { SubcomposeAsyncImageContent() },
    )
}
