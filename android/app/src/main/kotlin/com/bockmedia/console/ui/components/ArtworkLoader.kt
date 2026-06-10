package com.bockmedia.console.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.bockmedia.console.data.repository.BockMediaRepository

/**
 * Resolves artwork URL for a list/grid row. Prefer [artPath] from API responses to
 * avoid extra network round-trips; fall back to playlist/artist/album lookup only when needed.
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
): String? {
    val artUrl by produceState<String?>(
        initialValue = null,
        artPath,
        playlistId,
        artistName,
        albumName,
        albumArtist,
        variantKey,
    ) {
        value = when {
            !playlistId.isNullOrBlank() -> repository.artworkUrlForPlaylist(playlistId, playlistId)
            !artPath.isNullOrBlank() -> repository.artworkUrl(artPath)
            !albumName.isNullOrBlank() -> repository.resolveAlbumArtUrl(albumName, albumArtist ?: artistName)
            !artistName.isNullOrBlank() -> repository.resolveArtistArtUrl(artistName)
            else -> null
        }
    }
    return artUrl
}
