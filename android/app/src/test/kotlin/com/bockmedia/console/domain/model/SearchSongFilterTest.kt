package com.bockmedia.console.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSongFilterTest {

    @Test
    fun excludesAlbumTracklistAndSoundtrackSuffixTitles() {
        assertFalse(matchesSearchSongTitle("mamma", "Waterloo", "Mamma Mia!"))
        assertFalse(matchesSearchSongTitle("mamma", "Dancing Queen", "Mamma Mia! Mania"))
        assertFalse(
            matchesSearchSongTitle(
                "mamma",
                "Waterloo - From Mamma Mia! Here We Go Again",
                "Mamma Mia!",
            ),
        )
        assertTrue(matchesSearchSongTitle("mamma", "Mamma Mia", "[2001] ABBA"))
        assertTrue(matchesSearchSongTitle("mamma", "Mamma Mia - Radio Version", "[2010] Hits"))
    }

    @Test
    fun filterSearchSongHits_appliesRules() {
        val filtered = filterSearchSongHits(
            "mamma",
            listOf(
                com.bockmedia.console.data.api.dto.SearchHit(
                    title = "Waterloo",
                    album = "Mamma Mia!",
                    path = "/a.mp3",
                ),
                com.bockmedia.console.data.api.dto.SearchHit(
                    title = "Mamma Mia",
                    album = "[2001] ABBA",
                    path = "/b.mp3",
                ),
            ),
        )
        assertTrue(filtered.single().title == "Mamma Mia")
    }
}
