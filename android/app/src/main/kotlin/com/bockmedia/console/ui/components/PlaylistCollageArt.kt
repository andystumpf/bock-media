package com.bockmedia.console.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkCache

@Composable
fun PlaylistTileArt(
    repository: BockMediaRepository,
    playlistId: String,
    title: String,
    artPath: String? = null,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    fallbackFontSize: TextUnit = 18.sp,
    sizePx: Int = TILE_ART_SIZE_PX,
) {
    val cachedCollage = remember(playlistId) { HomeArtworkCache.playlistCollagePaths(playlistId).orEmpty() }
    val collagePaths by produceState(initialValue = cachedCollage, playlistId) {
        if (value.isNotEmpty()) return@produceState
        value = repository.playlistCollageMediaPaths(playlistId)
    }
    val peekUrl = remember(playlistId, artPath, collagePaths) {
        val path = artPath?.takeIf { it.isNotBlank() } ?: collagePaths.firstOrNull()
        repository.peekBaseUrl()?.let { base ->
            path?.let { com.bockmedia.console.data.local.AppPreferences.artworkUrl(base, it, sizePx) }
        }
    }
    if (peekUrl != null) {
        PlaylistCollageArt(
            repository = repository,
            title = title,
            artPaths = collagePaths,
            fallbackArtUrl = peekUrl,
            modifier = modifier,
            shape = shape,
            fallbackFontSize = fallbackFontSize,
            sizePx = sizePx,
        )
        return
    }
    val fallbackUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = artPath ?: collagePaths.firstOrNull(),
        playlistId = playlistId,
        variantKey = playlistId,
        sizePx = sizePx,
    )
    PlaylistCollageArt(
        repository = repository,
        title = title,
        artPaths = collagePaths,
        fallbackArtUrl = fallbackUrl,
        modifier = modifier,
        shape = shape,
        fallbackFontSize = fallbackFontSize,
        sizePx = sizePx,
    )
}

@Composable
fun PlaylistCollageArt(
    repository: BockMediaRepository,
    title: String,
    artPaths: List<String>,
    fallbackArtUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    fallbackFontSize: TextUnit = 18.sp,
    tileFallbackFontSize: TextUnit = 10.sp,
    sizePx: Int = TILE_ART_SIZE_PX,
) {
    val distinctPaths = remember(artPaths) { artPaths.filter { it.isNotBlank() }.distinct().take(4) }
    if (distinctPaths.size >= 2) {
        Column(
            modifier = modifier.clip(shape),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            distinctPaths.chunked(2).take(2).forEach { rowPaths ->
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rowPaths.forEach { path ->
                        CollagePathThumb(
                            repository = repository,
                            title = title,
                            artPath = path,
                            sizePx = sizePx,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            fallbackFontSize = tileFallbackFontSize,
                        )
                    }
                    if (rowPaths.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        BockArtwork(
            model = fallbackArtUrl,
            title = title,
            modifier = modifier.clip(shape),
            shape = shape,
            fallbackFontSize = fallbackFontSize,
        )
    }
}

@Composable
private fun CollagePathThumb(
    repository: BockMediaRepository,
    title: String,
    artPath: String,
    sizePx: Int,
    modifier: Modifier = Modifier,
    fallbackFontSize: TextUnit = 10.sp,
) {
    val artUrl = rememberArtworkUrl(
        repository = repository,
        title = title,
        artPath = artPath,
        variantKey = artPath,
        sizePx = sizePx,
    )
    BockArtwork(
        model = artUrl,
        title = title,
        modifier = modifier,
        shape = RoundedCornerShape(2.dp),
        fallbackFontSize = fallbackFontSize,
    )
}
