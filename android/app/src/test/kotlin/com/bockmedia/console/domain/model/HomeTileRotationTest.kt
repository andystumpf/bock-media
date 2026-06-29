package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeTileRotationTest {
    @org.junit.Before
    fun setUp() {
        HomeTileEngagement.useInMemoryForTesting()
        HomeTileEngagement.resetForTesting()
    }

    @Test
    fun apply_replacesStaleTileWithFreshPlaylist() {
        val playlists = (1..12).map { i ->
            PlaylistSummary(id = "pl-$i", name = "Playlist $i", tracks = i + 3)
        }
        val staleCard = HomeCard(
            id = "pl-pl-1",
            title = "Playlist 1",
            subtitle = "Old",
            playlistId = "pl-1",
            playTarget = PlayTarget.Playlist("pl-1", "Playlist 1"),
            kind = HomeSectionKind.Discover,
        )
        val feed = HomeFeed(
            listOf(
                HomeSection("discover", "Discover", HomeSectionKind.Discover, listOf(staleCard)),
            ),
        )
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 99,
        )
        val now = System.currentTimeMillis()
        val staleSince = now - HomeTileEngagement.STALE_MS - 60_000
        HomeTileEngagement.putForTesting(
            staleCard.id,
            TileEngagementEntry(firstSeenMs = staleSince),
        )
        org.junit.Assert.assertTrue(HomeTileEngagement.isStale(staleCard.id, now))

        val rotated = HomeTileRotation.apply(feed, input, now = now)
        val card = rotated.sections.single().cards.single()
        assertNotEquals(staleCard.id, card.id)
        assertEquals(HomeSectionKind.Discover, card.kind)
    }

    @Test
    fun apply_keepsFreshTile() {
        val playlists = listOf(PlaylistSummary(id = "pl-1", name = "Playlist 1", tracks = 10))
        val card = HomeCard(
            id = "pl-pl-1",
            title = "Playlist 1",
            subtitle = "Fresh",
            playlistId = "pl-1",
            playTarget = PlayTarget.Playlist("pl-1", "Playlist 1"),
            kind = HomeSectionKind.Discover,
        )
        val feed = HomeFeed(listOf(HomeSection("discover", "Discover", HomeSectionKind.Discover, listOf(card))))
        val input = HomeFeedInput(
            history = emptyList(),
            analytics = null,
            allPlaylists = playlists,
            smartPlaylists = emptyList(),
            favorites = emptyList(),
            dashboard = null,
            shuffleSeed = 1,
        )
        val now = System.currentTimeMillis()
        HomeTileEngagement.putForTesting(
            card.id,
            TileEngagementEntry(firstSeenMs = now - 60_000, lastSelectedMs = now - 60_000),
        )

        val rotated = HomeTileRotation.apply(feed, input, now = now)
        assertEquals(card.id, rotated.sections.single().cards.single().id)
    }
}
