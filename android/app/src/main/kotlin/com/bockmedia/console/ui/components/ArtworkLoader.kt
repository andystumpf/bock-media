package com.bockmedia.console.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.ArtworkPaths.TILE_SIZE_PX
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.PlayTarget

/** @see TILE_SIZE_PX */
const val TILE_ART_SIZE_PX = TILE_SIZE_PX

/**
 * Resolves artwork URL for a list/grid row. When [playlistId] is set, uses the playlist
 * cover API first (matches home/web); otherwise prefers [artPath], then artist/album lookup.
 *
 * [sizePx] optionally requests a server-side downscaled thumbnail (/artwork?size=N). Left null
 * by default so every art builder produces an identical URL — a single, consistent Coil cache
 * key — and the server's long-lived Cache-Control makes the first fetch the only network hit.
 */
@Composable
fun rememberArtworkUrl(
    repository: BockMediaRepository,
    title: String,
    artPath: String? = null,
    playlistId: String? = null,
    artistName: String? = null,
    genreName: String? = null,
    albumName: String? = null,
    albumArtist: String? = null,
    variantKey: String = title,
    sizePx: Int = TILE_ART_SIZE_PX,
    cardId: String? = null,
    playTarget: PlayTarget? = null,
): String? {
    val netGen = NetworkReachability.generation
    val baseEpoch = repository.baseUrlEpoch
    val baseUrl = remember(repository, netGen, baseEpoch) { repository.peekBaseUrl() }
    fun peek(): String? {
        val base = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        playlistId?.let { HomeArtworkCache.playlistPath(it) }
            ?.let { return AppPreferences.artworkUrl(base, it, sizePx) }
        return artPath?.takeIf { it.isNotBlank() }
            ?.let { AppPreferences.artworkUrl(base, it, sizePx) }
    }
    var artUrl by remember(artPath, playlistId, artistName, genreName, albumName, albumArtist, variantKey, baseUrl, netGen, baseEpoch, sizePx, cardId, playTarget) {
        mutableStateOf(peek())
    }
    LaunchedEffect(artPath, playlistId, artistName, genreName, albumName, albumArtist, variantKey, baseUrl, netGen, baseEpoch, sizePx, cardId, playTarget) {
        artUrl = peek()
        if (artUrl != null) return@LaunchedEffect
        artUrl = when {
            !playlistId.isNullOrBlank() -> repository.artworkUrlForPlaylist(playlistId, variantKey, sizePx)
                ?: artPath?.let { repository.artworkUrl(it, sizePx) }
            !artPath.isNullOrBlank() -> repository.artworkUrl(artPath, sizePx)
            !genreName.isNullOrBlank() -> repository.resolveGenreArtByName(genreName, sizePx)
            !albumName.isNullOrBlank() -> repository.resolveAlbumArtUrl(albumName, albumArtist ?: artistName, sizePx)
            !artistName.isNullOrBlank() -> repository.resolveArtistArtUrl(artistName, sizePx)
            !cardId.isNullOrBlank() && playTarget != null ->
                repository.resolveHomeCardArtUrl(cardId, artPath, playlistId, playTarget, sizePx)
            else -> null
        }
    }
    return artUrl
}
