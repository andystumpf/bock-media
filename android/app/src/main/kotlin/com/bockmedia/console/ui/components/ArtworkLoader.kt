package com.bockmedia.console.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkCache

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
    val baseUrl = remember(repository) { repository.peekBaseUrl() }
    val syncPeek = remember(artPath, playlistId, baseUrl, variantKey) {
        artPath?.takeIf { it.isNotBlank() }?.let { AppPreferences.artworkUrl(baseUrl ?: return@let null, it) }
            ?: playlistId?.let { HomeArtworkCache.playlistPath(it) }
                ?.let { AppPreferences.artworkUrl(baseUrl ?: return@let null, it) }
    }
    var artUrl by remember(artPath, playlistId, artistName, albumName, albumArtist, variantKey, baseUrl) {
        mutableStateOf(syncPeek)
    }
    LaunchedEffect(artPath, playlistId, artistName, albumName, albumArtist, variantKey, baseUrl) {
        if (artUrl != null) return@LaunchedEffect
        artUrl = when {
            !playlistId.isNullOrBlank() -> repository.artworkUrlForPlaylist(playlistId, variantKey)
            !artPath.isNullOrBlank() -> repository.artworkUrl(artPath)
            !albumName.isNullOrBlank() -> repository.resolveAlbumArtUrl(albumName, albumArtist ?: artistName)
            !artistName.isNullOrBlank() -> repository.resolveArtistArtUrl(artistName)
            else -> null
        }
    }
    return artUrl
}
