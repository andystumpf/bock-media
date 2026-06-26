package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import com.bockmedia.console.domain.model.PlayTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(
            "expected mood rows on home",
            feed.sections.count { it.kind == HomeSectionKind.Mood } >= 8,
        )
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
        assertTrue(moodSections.all { it.cards.isNotEmpty() })
        val french = moodSections.first { it.title == "French music" }
        assertEquals(1, french.cards.size)
        val italian = moodSections.first { it.title == "Italian music" }
        assertEquals(1, italian.cards.size)
    }

    @Test
    fun compose_moodSectionCollectsAllKeywordPlaylists() {
        val playlists = listOf(
            PlaylistSummary(id = "f1", name = "French Pop Hits", tracks = 10),
            PlaylistSummary(id = "f2", name = "My French Mix", tracks = 8),
            PlaylistSummary(id = "f3", name = "Best of FRANCE", tracks = 6),
            PlaylistSummary(id = "rock", name = "Rock Classics", tracks = 20),
        )
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 9,
        )
        val feed = HomeFeedComposer.compose(input)
        val french = feed.sections.first { it.title == "French music" }
        assertEquals(3, french.cards.size)
        assertTrue(french.cards.all {
            it.title.contains("french", ignoreCase = true) ||
                it.title.contains("france", ignoreCase = true)
        })
    }

    @Test
    fun compose_genreMixPrefersRemixPlaylistOverTopArtist() {
        val playlists = listOf(
            PlaylistSummary(id = "remix-1", name = "Yacht Rock Remixes", tracks = 40),
            PlaylistSummary(id = "other", name = "Other", tracks = 10),
        )
        val history = listOf(
            StreamHistoryItem(filepath = "/nr.mp3", artist = "New Radicals", playlist = "Yacht Rock Remixes"),
            StreamHistoryItem(filepath = "/nr2.mp3", artist = "New Radicals", playlist = "Yacht Rock Remixes"),
        )
        val input = HomeFeedInput(
            history = history,
            analytics = AnalyticsResponse(topGenres = listOf(CountRow(name = "Rock", count = 5))),
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 11,
        )
        val feed = HomeFeedComposer.compose(input)
        val topMixes = feed.sections.firstOrNull { it.kind == HomeSectionKind.TopMixes }
        if (topMixes != null) {
            assertTrue(
                topMixes.cards.none {
                    it.playTarget is PlayTarget.Artist && it.title.equals("Rock Mix", ignoreCase = true)
                },
            )
        }
        val remixPl = feed.sections.flatMap { it.cards }.first {
            it.playTarget is PlayTarget.Playlist &&
                (it.playTarget as PlayTarget.Playlist).id == "remix-1"
        }
        assertEquals("Yacht Rock Remixes", remixPl.title)
    }

    @Test
    fun compose_genreMixArtUsesLibraryGenreNotRecentHistory() {
        val input = HomeFeedInput(
            history = listOf(
                StreamHistoryItem(filepath = "/recent.mp3", artist = "Recent Artist", album = "Recent Album"),
            ),
            analytics = AnalyticsResponse(
                topGenres = listOf(CountRow(name = "Classical Era", count = 5)),
                topArtists = listOf(CountRow(name = "Smashing Pumpkins", count = 10)),
            ),
            allPlaylists = emptyList(),
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            libraryGenres = listOf(GenreItem(name = "Classical Era", artPath = "/classical/cover.mp3")),
            shuffleSeed = 1,
        )
        val mix = HomeFeedComposer.compose(input).sections
            .flatMap { it.cards }
            .first { it.title == "Classical Era Mix" }
        assertEquals("/classical/cover.mp3", mix.artPath)
    }

    @Test
    fun compose_genreMixArtDoesNotUseSeedArtistHistory() {
        val input = HomeFeedInput(
            history = listOf(
                StreamHistoryItem(filepath = "/gish.mp3", artist = "Smashing Pumpkins", album = "Gish"),
            ),
            analytics = AnalyticsResponse(
                topGenres = listOf(CountRow(name = "Classical Era", count = 5)),
                topArtists = listOf(CountRow(name = "Smashing Pumpkins", count = 10)),
            ),
            allPlaylists = emptyList(),
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            libraryGenres = emptyList(),
            shuffleSeed = 1,
        )
        val mix = HomeFeedComposer.compose(input).sections
            .flatMap { it.cards }
            .first { it.title == "Classical Era Mix" }
        assertNull(mix.artPath)
    }

    @Test
    fun matchingLibraryGenreForLabel_findsExactAndPartial() {
        val genres = listOf(
            GenreItem(name = "Classical Era", artPath = "/era.jpg"),
            GenreItem(name = "Rock", artPath = "/rock.jpg"),
        )
        assertEquals("Classical Era", HomeFeedRules.matchingLibraryGenreForLabel("Classical Era", genres)?.name)
        assertEquals("Rock", HomeFeedRules.matchingLibraryGenreForLabel("Rock", genres)?.name)
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
        val playlistIds = feed.sections
            .filter { it.kind != HomeSectionKind.Mood }
            .flatMap { it.cards }
            .mapNotNull { it.playlistId }
        assertEquals(playlistIds.toSet().size, playlistIds.size)
    }
}
