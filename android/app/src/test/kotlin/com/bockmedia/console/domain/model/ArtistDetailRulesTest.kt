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
}
