package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AlbumItem

object ArtistDetailRules {
    fun isSingleOrEp(album: AlbumItem): Boolean {
        val name = album.name.lowercase()
        if (album.tracks in 1..4) return true
        return listOf(" ep", "ep ", "single", " live at ", "live from ", "session", " b-sides", "demo")
            .any { name.contains(it) }
    }

    fun splitAlbums(items: List<AlbumItem>): Pair<List<AlbumItem>, List<AlbumItem>> {
        val studio = mutableListOf<AlbumItem>()
        val singles = mutableListOf<AlbumItem>()
        for (album in items) {
            if (isSingleOrEp(album)) singles += album else studio += album
        }
        return studio to singles
    }

    fun yearRange(albums: List<AlbumItem>): String? {
        val years = albums.mapNotNull { it.year }.filter { it > 0 }
        if (years.isEmpty()) return null
        val min = years.min()
        val max = years.max()
        return if (min == max) min.toString() else "$min–$max"
    }

    fun matchesArtist(artist: String?, filter: String): Boolean {
        if (artist.isNullOrBlank()) return false
        return artist.equals(filter, ignoreCase = true) ||
            artist.contains(filter, ignoreCase = true) ||
            filter.contains(artist, ignoreCase = true)
    }
}
