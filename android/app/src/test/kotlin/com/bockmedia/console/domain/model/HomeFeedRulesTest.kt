package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.StreamHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedRulesTest {
    @Test
    fun isDailyMixName_matchesDailyMixVariants() {
        assertTrue(HomeFeedRules.isDailyMixName("Daily Mix 1"))
        assertTrue(HomeFeedRules.isDailyMixName("My Daylist"))
        assertFalse(HomeFeedRules.isDailyMixName("Rock Mix"))
    }

    @Test
    fun isExplicitRadioPlaylistName_excludesMixPlaylists() {
        assertFalse(HomeFeedRules.isExplicitRadioPlaylistName("Rock Mix"))
        assertFalse(HomeFeedRules.isExplicitRadioPlaylistName("Daily Mix 3"))
        assertFalse(HomeFeedRules.isExplicitRadioPlaylistName("Discover Weekly"))
        assertTrue(HomeFeedRules.isExplicitRadioPlaylistName("Jazz Radio"))
        assertTrue(HomeFeedRules.isExplicitRadioPlaylistName("Chill Station"))
    }

    @Test
    fun artPathForGenre_usesMatchingHistoryNotFirstTrack() {
        val history = listOf(
            StreamHistoryItem(filepath = "/a.mp3", artist = "One", sourceLabel = "Pop"),
            StreamHistoryItem(filepath = "/b.mp3", artist = "Two", sourceLabel = "Jazz radio"),
        )
        assertEquals("/b.mp3", HomeFeedRules.artPathForGenre(history, "Jazz"))
        assertNull(HomeFeedRules.artPathForGenre(history, "Classical"))
    }

    @Test
    fun topArtistForGenre_picksMostPlayedMatchingArtist() {
        val history = listOf(
            StreamHistoryItem(artist = "A", playlist = "Jazz Mix"),
            StreamHistoryItem(artist = "B", playlist = "Jazz Mix"),
            StreamHistoryItem(artist = "B", playlist = "Jazz Mix"),
        )
        assertEquals("B", HomeFeedRules.topArtistForGenre(history, "Jazz"))
    }
}
