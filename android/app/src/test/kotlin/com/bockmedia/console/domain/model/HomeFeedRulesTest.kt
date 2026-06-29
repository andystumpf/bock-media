package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.PlaylistSummary
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

    @Test
    fun isGenreMixPlaylistName_matchesRemixPlaylistsForGenre() {
        assertTrue(HomeFeedRules.isGenreMixPlaylistName("Yacht Rock Remixes", "Rock"))
        assertTrue(HomeFeedRules.isGenreMixPlaylistName("Trap Music 2024 Trap - Bass Remixes", "Trap"))
        assertTrue(HomeFeedRules.isGenreMixPlaylistName("Rock Mix", "Rock"))
        assertFalse(HomeFeedRules.isGenreMixPlaylistName("Yacht Rock Remixes", "Jazz"))
    }

    @Test
    fun isGenreMixPlaylistName_rejectsEraSubstringFalsePositives() {
        assertFalse(HomeFeedRules.isGenreMixPlaylistName("Generation X"))
        assertFalse(HomeFeedRules.isGenreMixPlaylistName("Taylor Swift  The Eras Tour Official Setlist"))
    }

    @Test
    fun bestGenreMixPlaylist_singleRemixPlaylistForRock() {
        val all = listOf(PlaylistSummary(id = "remix-1", name = "Yacht Rock Remixes", tracks = 40))
        assertTrue(HomeFeedRules.isGenreMixPlaylistName("Yacht Rock Remixes", "Rock"))
        assertEquals("remix-1", HomeFeedRules.bestGenreMixPlaylist(all, "Rock")?.id)
    }

    @Test
    fun bestGenreMixPlaylist_matchesRemixPlaylistForGenre() {
        val all = listOf(
            PlaylistSummary(id = "a", name = "Yacht Rock Remixes", tracks = 40),
            PlaylistSummary(id = "b", name = "Rock Mix", tracks = 10),
        )
        assertTrue(HomeFeedRules.isGenreMixPlaylistName("Rock Mix", "Rock"))
        assertEquals("b", HomeFeedRules.bestGenreMixPlaylist(all, "Rock")?.id)
    }

    @Test
    fun hasMixLikeName_usesWordBoundaries() {
        assertTrue(HomeFeedRules.hasMixLikeName("New Radicals Remix"))
        assertFalse(HomeFeedRules.hasMixLikeName("Remastered Hits"))
    }

    @Test
    fun artPathForPlaylistDistinct_skipsUsedPaths() {
        val history = listOf(
            StreamHistoryItem(filepath = "/a.mp3", playlist = "Classical Era Mix"),
            StreamHistoryItem(filepath = "/b.mp3", playlist = "Classical Era Mix"),
            StreamHistoryItem(filepath = "/c.mp3", playlist = "Rock Mix"),
        )
        val used = setOf("/a.mp3")
        assertEquals("/b.mp3", HomeFeedRules.artPathForPlaylistDistinct(history, "Classical Era Mix", used))
    }
}
