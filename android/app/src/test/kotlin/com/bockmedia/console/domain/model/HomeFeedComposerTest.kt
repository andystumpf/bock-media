package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedComposerTest {
    @Test
    fun compose_fillsFromLibraryWhenHistoryEmpty() {
        val playlists = (1..30).map { i ->
            PlaylistSummary(id = "pl-$i", name = "Playlist $i", tracks = i * 2)
        }
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 42,
        )
        val feed = HomeFeedComposer.compose(input)
        assertFalse(feed.sections.isEmpty())
        val playlistCards = feed.sections.flatMap { it.cards }.mapNotNull { it.playlistId }
        assertTrue(playlistCards.size >= 20)
    }

    @Test
    fun compose_includesExploreThemeForMatchingPlaylist() {
        val playlists = listOf(
            PlaylistSummary(id = "fr-1", name = "French Favorites", tracks = 42),
            PlaylistSummary(id = "it-1", name = "Italian Classics", tracks = 30),
        )
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 1,
        )
        val feed = HomeFeedComposer.compose(input)
        val explore = feed.sections.firstOrNull { it.kind == HomeSectionKind.ExploreThemes }
        assertTrue(explore != null)
        assertTrue(explore!!.cards.isNotEmpty())
    }

    @Test
    fun compose_includesMoodSections() {
        val playlists = listOf(
            PlaylistSummary(id = "dinner-1", name = "Sunday Dinner Jazz", tracks = 40),
            PlaylistSummary(id = "fr-1", name = "French Favorites", tracks = 42),
            PlaylistSummary(id = "road-1", name = "Road Trip Hits", tracks = 55),
        ) + (1..20).map { i ->
            PlaylistSummary(id = "pl-$i", name = "Playlist $i", tracks = i * 2)
        }
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 3,
        )
        val feed = HomeFeedComposer.compose(input)
        val moodSections = feed.sections.filter { it.kind == HomeSectionKind.Mood }
        assertEquals(8, moodSections.size)
        assertTrue(moodSections.any { it.title == "French music" })
        assertTrue(moodSections.any { it.title == "Road trip" })
        assertTrue(moodSections.all { it.cards.isNotEmpty() })
    }

    @Test
    fun compose_dedupesPlaylistsAcrossSections() {
        val playlists = listOf(
            PlaylistSummary(id = "a", name = "Alpha", tracks = 10),
            PlaylistSummary(id = "b", name = "Beta", tracks = 8),
            PlaylistSummary(id = "c", name = "Gamma", tracks = 6),
        )
        val history = listOf(
            StreamHistoryItem(playlist = "Alpha", filepath = "/a.mp3"),
            StreamHistoryItem(playlist = "Beta", filepath = "/b.mp3"),
        )
        val input = HomeFeedInput(
            history = history,
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 7,
        )
        val feed = HomeFeedComposer.compose(input)
        val playlistIds = feed.sections.flatMap { it.cards }.mapNotNull { it.playlistId }
        assertEquals(playlistIds.toSet().size, playlistIds.size)
    }
}
