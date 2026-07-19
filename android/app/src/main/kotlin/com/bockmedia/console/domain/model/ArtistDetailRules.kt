package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.RatingItem

enum class ArtistAlbumSort { Newest, Oldest, NameAz }

enum class ArtistDiscographyFilter(val label: String) {
    Studio("Studio"),
    Singles("Singles & EPs"),
    Live("Live"),
    Compilations("Compilations"),
    All("All"),
}

object ArtistDetailRules {
    fun isSingleOrEp(album: AlbumItem): Boolean {
        val name = album.name.lowercase()
        if (album.tracks in 1..4) return true
        return listOf(" ep", "ep ", "single", " live at ", "live from ", "session", " b-sides", "demo")
            .any { name.contains(it) }
    }

    fun isLiveAlbum(album: AlbumItem): Boolean {
        val name = album.name.lowercase()
        return name.contains("live") || name.contains("concert") || name.contains("unplugged")
    }

    fun isCompilationAlbum(album: AlbumItem, appearsOnNames: Set<String> = emptySet()): Boolean {
        if (album.name.lowercase() in appearsOnNames.map { it.lowercase() }.toSet()) return true
        val name = album.name.lowercase()
        return listOf("greatest hits", "best of", "anthology", "collection", "compilation", "various")
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

    fun filterDiscography(
        albums: List<AlbumItem>,
        filter: ArtistDiscographyFilter,
        appearsOnNames: Set<String> = emptySet(),
    ): List<AlbumItem> = when (filter) {
        ArtistDiscographyFilter.All -> albums
        ArtistDiscographyFilter.Studio -> albums.filter { !isSingleOrEp(it) && !isLiveAlbum(it) && !isCompilationAlbum(it, appearsOnNames) }
        ArtistDiscographyFilter.Singles -> albums.filter { isSingleOrEp(it) }
        ArtistDiscographyFilter.Live -> albums.filter { isLiveAlbum(it) }
        ArtistDiscographyFilter.Compilations -> albums.filter { isCompilationAlbum(it, appearsOnNames) }
    }

    fun latestAlbum(albums: List<AlbumItem>): AlbumItem? =
        albums.maxWithOrNull(
            compareBy<AlbumItem> { it.year ?: 0 }.thenBy { it.name.lowercase() },
        )

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

    fun sortAlbums(albums: List<AlbumItem>, sort: ArtistAlbumSort): List<AlbumItem> = when (sort) {
        ArtistAlbumSort.Newest -> albums.sortedWith(
            compareByDescending<AlbumItem> { it.year ?: 0 }.thenBy { it.name.lowercase() },
        )
        ArtistAlbumSort.Oldest -> albums.sortedWith(
            compareBy<AlbumItem> { if ((it.year ?: 0) > 0) it.year else Int.MAX_VALUE }.thenBy { it.name.lowercase() },
        )
        ArtistAlbumSort.NameAz -> albums.sortedBy { it.name.lowercase() }
    }

    fun ratedTracksForArtist(rated: List<RatingItem>, artist: String): List<RatingItem> =
        highlyRatedTracksForArtist(rated, artist)

    fun highlyRatedTracksForArtist(rated: List<RatingItem>, vararg artists: String): List<RatingItem> {
        val names = artists.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptyList()
        return rated.filter { row ->
            row.stars >= 3 &&
                row.id.isNotBlank() &&
                names.any { matchesArtist(row.artist, it) }
        }.sortedByDescending { it.stars }
    }

    fun topGenresFromTracks(genres: List<String?>, limit: Int = 12): List<String> =
        genres.mapNotNull { it?.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it.lowercase() }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key.replaceFirstChar { c -> c.uppercaseChar() } }
            .distinct()
            .take(limit)

    fun formatLibraryCount(n: Int): String = when {
        n >= 1_000_000 -> "${"%.1f".format(n / 1_000_000.0)}M"
        n >= 1_000 -> "${n / 1_000}K"
        else -> n.toString()
    }

    fun isCompilationAlbumName(albumName: String, albumArtist: String?): Boolean {
        val artist = albumArtist?.trim().orEmpty()
        if (artist.isBlank()) return false
        return listOf("various", "va", "soundtrack", "ost")
            .any { artist.equals(it, ignoreCase = true) || artist.contains(it, ignoreCase = true) }
    }
}
