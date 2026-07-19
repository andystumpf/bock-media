package com.bockmedia.console.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSectionPinsApplierTest {
    @Test
    fun prependsPinnedPlaylistToSection() {
        val feed = HomeFeed(
            sections = listOf(
                HomeSection(
                    id = "mood-sunday-morning",
                    title = "Sunday morning",
                    kind = HomeSectionKind.Mood,
                    cards = listOf(
                        homeCard("old", "Old mix"),
                        homeCard("other", "Other mix"),
                    ),
                ),
            ),
        )
        val pins = listOf(
            HomeSectionPin(
                sectionId = "mood-sunday-morning",
                playlistId = "new-pl",
                playlistName = "Summer weekday mornings",
            ),
        )
        val playlists = listOf(
            com.bockmedia.console.data.api.dto.PlaylistSummary(
                id = "new-pl",
                name = "Summer weekday mornings",
                tracks = 12,
            ),
        )
        val out = HomeSectionPinsApplier.apply(feed, pins, playlists)
        val cards = out.sections.first().cards
        assertEquals("new-pl", cards.first().playlistId)
        assertEquals(3, cards.size)
        assertEquals(0, cards.indexOfFirst { it.playlistId == "new-pl" })
    }

    private fun homeCard(id: String, title: String) = HomeCard(
        id = "pl-$id",
        title = title,
        playlistId = id,
        playTarget = PlayTarget.Playlist(id, title),
        kind = HomeSectionKind.Mood,
    )
}
