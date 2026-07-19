package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.api.dto.SearchResponse

/** Keep prefix/acronym song matches; drop album tracklist rows and soundtrack suffix titles. */
fun filterSearchSongHits(query: String, songs: List<SearchHit>): List<SearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    return songs.filter { hit ->
        matchesSearchSongTitle(q, hit.title, hit.album, hit.artist, hit.genre)
    }
}

internal fun matchesSearchSongTitle(
    q: String,
    title: String?,
    album: String?,
    artist: String? = null,
    genre: String? = null,
): Boolean {
    if (SearchPrefixMatch.fieldMatchesQuery(q, artist)) return true
    if (genreMatchesQuery(q, genre)) return true
    if (!SearchPrefixMatch.fieldMatchesQuery(q, title)) return false
    val al = album?.lowercase()?.trim().orEmpty()
    val tl = title?.lowercase()?.trim().orEmpty()
    for (sep in listOf(" - from ", " – from ")) {
        val idx = tl.indexOf(sep)
        if (idx >= 0) {
            val primary = tl.substring(0, idx)
            val suffix = tl.substring(idx + sep.length)
            if (SearchPrefixMatch.fieldMatchesQuery(q, al) &&
                !SearchPrefixMatch.fieldMatchesQuery(q, primary) &&
                SearchPrefixMatch.fieldMatchesQuery(q, suffix)
            ) {
                return false
            }
        }
    }
    val dash = tl.indexOf(" - ")
    if (dash >= 0 && SearchPrefixMatch.fieldMatchesQuery(q, al)) {
        val primary = tl.substring(0, dash)
        val suffix = tl.substring(dash + 3)
        if (!SearchPrefixMatch.fieldMatchesQuery(q, primary) &&
            SearchPrefixMatch.fieldMatchesQuery(q, suffix)
        ) {
            return false
        }
    }
    return true
}

fun SearchResponse.hasAnyResults(): Boolean =
    songs.isNotEmpty() || artists.isNotEmpty() || albums.isNotEmpty() ||
        playlists.isNotEmpty() || genres.isNotEmpty() || smartPlaylists.isNotEmpty()

/** Client-side narrow when server returns empty but a shorter prefix still matched. */
fun SearchResponse.narrowToQuery(query: String): SearchResponse {
    val q = query.trim()
    return copy(
        songs = songs.filter { matchesSearchSongTitle(q, it.title, it.album, it.artist, it.genre) },
        artists = artists.filter { SearchPrefixMatch.fieldMatchesQuery(q, it.name) },
        albums = albums.filter {
            SearchPrefixMatch.fieldMatchesQuery(q, it.name) ||
                SearchPrefixMatch.fieldMatchesQuery(q, it.artist)
        },
        playlists = playlists.filter { SearchPrefixMatch.fieldMatchesQuery(q, it.name) },
        smartPlaylists = smartPlaylists.filter { SearchPrefixMatch.fieldMatchesQuery(q, it.name) },
        genres = genres.filter { genreMatchesQuery(q, it.name) },
    )
}

/** Genre bucket: prefix match plus substring on folded text (french ↔ Français). */
internal fun genreMatchesQuery(q: String, name: String?): Boolean {
    if (SearchPrefixMatch.fieldMatchesQuery(q, name)) return true
    val qc = SearchPrefixMatch.compact(q)
    val nc = SearchPrefixMatch.compact(name ?: "")
    return qc.length >= 2 && nc.contains(qc)
}
