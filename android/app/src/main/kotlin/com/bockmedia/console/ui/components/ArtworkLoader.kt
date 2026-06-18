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
import com.bockmedia.console.domain.model.HomeArtworkCache

/**
 * Resolves artwork URL for a list/grid row. Prefer [artPath] from API responses to
 * avoid extra network round-trips; fall back to playlist/artist/album lookup only when needed.
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
    albumName: String? = null,
    albumArtist: String? = null,
    variantKey: String = title,
    sizePx: Int? = null,
): String? {
    val netGen = NetworkReachability.generation
    val baseUrl = remember(repository, netGen) { repository.peekBaseUrl() }
    val syncPeek = remember(artPath, playlistId, baseUrl, variantKey, netGen, sizePx) {
        artPath?.takeIf { it.isNotBlank() }?.let { AppPreferences.artworkUrl(baseUrl ?: return@let null, it, sizePx) }
            ?: playlistId?.let { HomeArtworkCache.playlistPath(it) }
                ?.let { AppPreferences.artworkUrl(baseUrl ?: return@let null, it, sizePx) }
    }
    var artUrl by remember(artPath, playlistId, artistName, albumName, albumArtist, variantKey, baseUrl, netGen, sizePx) {
        mutableStateOf(syncPeek)
    }
    LaunchedEffect(artPath, playlistId, artistName, albumName, albumArtist, variantKey, baseUrl, netGen, sizePx) {
        artUrl = syncPeek
        if (artUrl != null) return@LaunchedEffect
        artUrl = when {
            !playlistId.isNullOrBlank() -> repository.artworkUrlForPlaylist(playlistId, variantKey, sizePx)
            !artPath.isNullOrBlank() -> repository.artworkUrl(artPath, sizePx)
            !albumName.isNullOrBlank() -> repository.resolveAlbumArtUrl(albumName, albumArtist ?: artistName, sizePx)
            !artistName.isNullOrBlank() -> repository.resolveArtistArtUrl(artistName, sizePx)
            else -> null
        }
    }
    return artUrl
}
