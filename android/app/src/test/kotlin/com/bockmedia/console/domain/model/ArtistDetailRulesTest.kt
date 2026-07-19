package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.domain.model.ArtistDetailRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistDetailRulesTest {
    @Test
    fun splitsSinglesFromStudioAlbums() {
        val albums = listOf(
            AlbumItem(name = "Beta Love", artist = "Ra Ra Riot", tracks = 11, year = 2013),
            AlbumItem(name = "The Orchard", artist = "Ra Ra Riot", tracks = 10, year = 2010),
            AlbumItem(name = "Can You Tell EP", artist = "Ra Ra Riot", tracks = 4, year = 2008),
        )
        val (studio, singles) = ArtistDetailRules.splitAlbums(albums)
        assertEquals(2, studio.size)
        assertEquals(1, singles.size)
        assertTrue(ArtistDetailRules.isSingleOrEp(singles.first()))
    }

    @Test
    fun yearRangeCollapsesSingleYear() {
        val albums = listOf(
            AlbumItem(name = "A", artist = "X", tracks = 10, year = 2020),
            AlbumItem(name = "B", artist = "X", tracks = 10, year = 2020),
        )
        assertEquals("2020", ArtistDetailRules.yearRange(albums))
    }

    @Test
    fun yearRangeSpansYears() {
        val albums = listOf(
            AlbumItem(name = "A", artist = "X", tracks = 10, year = 2008),
            AlbumItem(name = "B", artist = "X", tracks = 10, year = 2020),
        )
        assertEquals("2008–2020", ArtistDetailRules.yearRange(albums))
    }

    @Test
    fun matchesArtistIsCaseInsensitive() {
        assertTrue(ArtistDetailRules.matchesArtist("Ra Ra Riot", "ra ra riot"))
        assertFalse(ArtistDetailRules.matchesArtist("Other", "Ra Ra Riot"))
    }

    @Test
    fun sortAlbumsNewestFirst() {
        val albums = listOf(
            AlbumItem(name = "B", artist = "X", tracks = 10, year = 2010),
            AlbumItem(name = "A", artist = "X", tracks = 10, year = 2020),
        )
        val sorted = ArtistDetailRules.sortAlbums(albums, ArtistAlbumSort.Newest)
        assertEquals("A", sorted.first().name)
    }

    @Test
    fun ratedTracksForArtistFiltersByNameAndStars() {
        val rated = listOf(
            com.bockmedia.console.data.api.dto.RatingItem(id = "p1", stars = 5, title = "T1", artist = "Radiohead"),
            com.bockmedia.console.data.api.dto.RatingItem(id = "p2", stars = 2, title = "T2", artist = "Radiohead"),
            com.bockmedia.console.data.api.dto.RatingItem(id = "p3", stars = 4, title = "T3", artist = "Other"),
        )
        val out = ArtistDetailRules.ratedTracksForArtist(rated, "radiohead")
        assertEquals(1, out.size)
        assertEquals("p1", out.first().id)
    }

    @Test
    fun highlyRatedTracksForArtistMatchesAlternateName() {
        val rated = listOf(
            com.bockmedia.console.data.api.dto.RatingItem(id = "p1", stars = 4, title = "T1", artist = "The Smashing Pumpkins"),
        )
        val out = ArtistDetailRules.highlyRatedTracksForArtist(rated, "Smashing Pumpkins", "The Smashing Pumpkins")
        assertEquals(1, out.size)
    }

    @Test
    fun topGenresFromTracksCountsMostCommon() {
        val genres = ArtistDetailRules.topGenresFromTracks(listOf("Rock", "Rock", "Jazz"))
        assertEquals("Rock", genres.first())
    }

    @Test
    fun filterDiscographyLiveAlbums() {
        val albums = listOf(
            AlbumItem(name = "Live at Wembley", artist = "X", tracks = 12, year = 2010),
            AlbumItem(name = "Studio LP", artist = "X", tracks = 10, year = 2010),
        )
        val live = ArtistDetailRules.filterDiscography(albums, ArtistDiscographyFilter.Live)
        assertEquals(1, live.size)
        assertTrue(live.first().name.contains("Live"))
    }

    @Test
    fun latestAlbumPicksNewestYear() {
        val albums = listOf(
            AlbumItem(name = "Old", artist = "X", tracks = 10, year = 2000),
            AlbumItem(name = "New", artist = "X", tracks = 10, year = 2020),
        )
        assertEquals("New", ArtistDetailRules.latestAlbum(albums)?.name)
    }

    @Test
    fun isCompilationAlbumNameDetectsVariousArtists() {
        assertTrue(ArtistDetailRules.isCompilationAlbumName("Hits", "Various Artists"))
    }
}
