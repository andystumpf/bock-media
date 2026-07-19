package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchResponse

enum class SearchTopResultKind { Song, Artist, Album, Playlist }

data class SearchTopResult(
    val kind: SearchTopResultKind,
    val title: String,
    val subtitle: String?,
    val hit: SearchHit,
)

/** Best single match for Spotify-style "Top result" hero row. */
fun pickTopSearchResult(response: SearchResponse): SearchTopResult? {
    response.songs.firstOrNull()?.let { hit ->
        val title = hit.title ?: hit.name ?: return@let null
        return SearchTopResult(
            kind = SearchTopResultKind.Song,
            title = title,
            subtitle = listOfNotNull(hit.artist, hit.album).joinToString(" · ").ifBlank { null },
            hit = hit,
        )
    }
    response.artists.firstOrNull()?.let { hit ->
        val title = hit.name ?: return@let null
        return SearchTopResult(
            kind = SearchTopResultKind.Artist,
            title = title,
            subtitle = hit.albums?.let { if (it == 1) "1 album" else "$it albums" },
            hit = hit,
        )
    }
    response.albums.firstOrNull()?.let { hit ->
        val title = hit.name ?: hit.album ?: return@let null
        return SearchTopResult(
            kind = SearchTopResultKind.Album,
            title = title,
            subtitle = hit.artist,
            hit = hit,
        )
    }
    response.playlists.firstOrNull()?.let { hit ->
        val title = hit.name ?: return@let null
        return SearchTopResult(
            kind = SearchTopResultKind.Playlist,
            title = title,
            subtitle = "Playlist",
            hit = hit,
        )
    }
    return null
}
