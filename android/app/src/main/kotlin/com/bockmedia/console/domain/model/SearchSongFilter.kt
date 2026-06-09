package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchHit

/** Keep song-title matches; drop album tracklist rows and soundtrack suffix titles. */
fun filterSearchSongHits(query: String, songs: List<SearchHit>): List<SearchHit> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    return songs.filter { hit ->
        matchesSearchSongTitle(q, hit.title, hit.album)
    }
}

internal fun matchesSearchSongTitle(q: String, title: String?, album: String?): Boolean {
    val tl = title?.lowercase()?.trim().orEmpty()
    if (q !in tl) return false
    val al = album?.lowercase()?.trim().orEmpty()
    for (sep in listOf(" - from ", " – from ")) {
        val idx = tl.indexOf(sep)
        if (idx >= 0) {
            val primary = tl.substring(0, idx)
            val suffix = tl.substring(idx + sep.length)
            if (q in al && q !in primary && q in suffix) return false
        }
    }
    val dash = tl.indexOf(" - ")
    if (dash >= 0 && q in al) {
        val primary = tl.substring(0, dash)
        val suffix = tl.substring(dash + 3)
        if (q !in primary && q in suffix) return false
    }
    return true
}
