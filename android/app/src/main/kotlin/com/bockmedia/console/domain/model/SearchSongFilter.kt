package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchHit

/** Keep prefix/acronym song matches; drop album tracklist rows and soundtrack suffix titles. */
fun filterSearchSongHits(query: String, songs: List<SearchHit>): List<SearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    return songs.filter { hit ->
        matchesSearchSongTitle(q, hit.title, hit.album, hit.artist)
    }
}

internal fun matchesSearchSongTitle(
    q: String,
    title: String?,
    album: String?,
    artist: String? = null,
): Boolean {
    if (SearchPrefixMatch.fieldMatchesQuery(q, artist)) return true
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
