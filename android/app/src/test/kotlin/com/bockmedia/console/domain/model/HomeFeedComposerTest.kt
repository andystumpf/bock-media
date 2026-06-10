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
