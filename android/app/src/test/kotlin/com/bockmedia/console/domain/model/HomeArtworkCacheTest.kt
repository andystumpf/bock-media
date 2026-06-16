package com.bockmedia.console.domain.model

import com.bockmedia.console.domain.model.PlayTarget.Playlist
import org.junit.Assert.assertFalse
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
    fun isFullyWarmed_falseWhenPathsMissing() {
        HomeArtworkCache.storePlaylistPath("p1", "/mnt/bock/Music/a.mp3")
        assertFalse(HomeArtworkCache.isFullyWarmed(listOf(cardA, cardB)))
    }

    @Test
    fun isFullyWarmed_trueWhenAllPathsKnown() {
        HomeArtworkCache.storePlaylistPath("p1", "/mnt/bock/Music/a.mp3")
        HomeArtworkCache.storePlaylistPath("p2", "/mnt/bock/Music/b.mp3")
        assertTrue(HomeArtworkCache.isFullyWarmed(listOf(cardA, cardB)))
    }

    @Test
    fun extractMediaPath_stripsHost() {
        val path = ArtworkPaths.extractMediaPath(
            "http://192.168.1.187:3001/artwork/mnt/bock/Music/song.mp3",
        )
        assertTrue(path?.contains("song.mp3") == true)
    }
}
