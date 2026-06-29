package com.bockmedia.console.domain.model

import com.bockmedia.console.domain.model.PlayTarget.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeArtworkCacheTest {
    private val cardA = HomeCard(
        id = "a",
        title = "A",
        playTarget = Playlist("p1", "A"),
        kind = HomeSectionKind.JumpBackIn,
        playlistId = "p1",
    )
    private val cardB = HomeCard(
        id = "b",
        title = "B",
        playTarget = Playlist("p2", "B"),
        kind = HomeSectionKind.JumpBackIn,
        playlistId = "p2",
    )

    @Before
    fun reset() {
        HomeArtworkCache.invalidate()
    }

    @Test
    fun mediaPathFor_playlistCardUsesInlineCoverThenCachedCover() {
        // artPath now carries the server's first-track cover from /api/playlists, so a
        // playlist tile paints from it immediately (no per-tile cover lookup).
        val card = cardA.copy(artPath = "/list/first-track.mp3")
        assertEquals("/list/first-track.mp3", HomeArtworkCache.mediaPathFor(card))
        // A freshly fetched playlist cover takes precedence over the inline one.
        HomeArtworkCache.storePlaylistPath("p1", "/fetched/cover.mp3")
        assertEquals("/fetched/cover.mp3", HomeArtworkCache.mediaPathFor(card))
    }

    @Test
    fun mediaPathFor_inlineArtPathBeforeWarm() {
        val mix = HomeCard(
            id = "mix-rock",
            title = "Rock Mix",
            artPath = "/demo/music/rock-cover.mp3",
            playTarget = PlayTarget.Artist("Some Artist"),
            kind = HomeSectionKind.TopMixes,
        )
        assertEquals("/demo/music/rock-cover.mp3", HomeArtworkCache.mediaPathFor(mix))
    }

    @Test
    fun mediaPathFor_playlistCardWithoutArtIsNull() {
        assertNull(HomeArtworkCache.mediaPathFor(cardB))
    }

    @Test
    fun isFullyWarmed_falseWhenPathsMissing() {
        HomeArtworkCache.storePlaylistPath("p1", "/demo/music/a.mp3")
        assertFalse(HomeArtworkCache.isFullyWarmed(listOf(cardA, cardB)))
    }

    @Test
    fun isFullyWarmed_trueWhenAllPathsKnown() {
        HomeArtworkCache.storePlaylistPath("p1", "/demo/music/a.mp3")
        HomeArtworkCache.storePlaylistPath("p2", "/demo/music/b.mp3")
        assertTrue(HomeArtworkCache.isFullyWarmed(listOf(cardA, cardB)))
    }

    @Test
    fun extractMediaPath_stripsHost() {
        val path = ArtworkPaths.extractMediaPath(
            "http://127.0.0.1:3001/artwork/demo/music/song.mp3",
        )
        assertTrue(path?.contains("song.mp3") == true)
    }
}
