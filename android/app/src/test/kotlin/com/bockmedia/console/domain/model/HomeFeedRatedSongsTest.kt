package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.RatingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedRatedSongsTest {
    @Test
    fun compose_ratedSongsSection_groupsByStar() {
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = emptyList(),
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            ratedSongItems = listOf(
                RatingItem(kind = "song", id = "/a.mp3", stars = 5, title = "A"),
                RatingItem(kind = "song", id = "/b.mp3", stars = 5, title = "B"),
                RatingItem(kind = "song", id = "/c.mp3", stars = 2, title = "C"),
            ),
            dashboard = null,
            shuffleSeed = 1,
        )
        val feed = HomeFeedComposer.compose(input)
        val section = feed.sections.firstOrNull { it.kind == HomeSectionKind.RatedSongs }
        assertNotNull(section)
        assertEquals("Rated Songs", section!!.title)
        assertEquals(2, section.cards.size)
        assertEquals("5★ songs", section.cards[0].title)
        assertEquals("rated-stars-5", section.cards[0].playlistId)
        assertEquals("2 tracks", section.cards[0].subtitle)
        assertTrue(section.cards[1].title.contains("2"))
    }
}
