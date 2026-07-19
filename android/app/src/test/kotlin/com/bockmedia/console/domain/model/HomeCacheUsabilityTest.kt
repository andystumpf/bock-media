package com.bockmedia.console.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCacheUsabilityTest {
    private fun layoutFeed(includeRatedSection: Boolean): HomeFeed {
        val rated = if (includeRatedSection) {
            listOf(
                HomeSection(
                    id = "rated-songs",
                    title = "Rated Songs",
                    kind = HomeSectionKind.RatedSongs,
                    cards = listOf(
                        HomeCard(
                            id = "rated-stars-5",
                            title = "5★ songs",
                            playTarget = PlayTarget.Playlist("rated-stars-5", "5★ songs"),
                            kind = HomeSectionKind.RatedSongs,
                        ),
                    ),
                ),
            )
        } else {
            emptyList()
        }
        return HomeFeed(
            sections = rated + listOf(
                HomeSection(
                    id = "recently-created",
                    title = "Recently Created",
                    kind = HomeSectionKind.RecentlyCreated,
                    cards = listOf(
                        HomeCard(
                            id = "pl-new",
                            title = "New Playlist",
                            playTarget = PlayTarget.Playlist("pl-new", "New Playlist"),
                            kind = HomeSectionKind.RecentlyCreated,
                        ),
                    ),
                ),
                HomeSection(
                    id = "browse-genres",
                    title = "Browse by genre",
                    kind = HomeSectionKind.BrowseGenres,
                    cards = listOf(
                        HomeCard(
                            id = "genre-rock",
                            title = "Rock",
                            playTarget = PlayTarget.Playlist("genre-rock", "Rock"),
                            kind = HomeSectionKind.BrowseGenres,
                        ),
                    ),
                ),
                HomeSection(
                    id = "recent-playlists",
                    title = "Recent playlists",
                    kind = HomeSectionKind.RecentPlaylists,
                    cards = listOf(
                        HomeCard(
                            id = "pl-1",
                            title = "Playlist 1",
                            playTarget = PlayTarget.Playlist("pl-1", "Playlist 1"),
                            kind = HomeSectionKind.RecentPlaylists,
                        ),
                    ),
                ),
                HomeSection(
                    id = "more-playlists",
                    title = "More playlists",
                    kind = HomeSectionKind.RecentPlaylists,
                    cards = listOf(
                        HomeCard(
                            id = "pl-2",
                            title = "Playlist 2",
                            playTarget = PlayTarget.Playlist("pl-2", "Playlist 2"),
                            kind = HomeSectionKind.RecentPlaylists,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun isUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsFalse_isUsable() {
        val feed = layoutFeed(includeRatedSection = false)
        assertTrue(feed.isUsableHomeCache(activeProfileLinked = true, hasRatedSongs = false))
    }

    @Test
    fun isUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsNull_isUsable() {
        val feed = layoutFeed(includeRatedSection = false)
        assertTrue(feed.isUsableHomeCache(activeProfileLinked = true, hasRatedSongs = null))
    }

    @Test
    fun isUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsTrue_isStale() {
        val feed = layoutFeed(includeRatedSection = false)
        assertFalse(feed.isUsableHomeCache(activeProfileLinked = true, hasRatedSongs = true))
    }

    @Test
    fun isUsableHomeCache_profileLinked_withRatedSection_isUsable() {
        val feed = layoutFeed(includeRatedSection = true)
        assertTrue(feed.isUsableHomeCache(activeProfileLinked = true, hasRatedSongs = true))
    }

    @Test
    fun shouldRefreshHomeForProfile_onlyWhenRatingsKnownButSectionMissing() {
        val feed = layoutFeed(includeRatedSection = false)
        assertFalse(shouldRefreshHomeForProfile(true, feed, hasRatedSongs = false))
        assertFalse(shouldRefreshHomeForProfile(true, feed, hasRatedSongs = null))
        assertTrue(shouldRefreshHomeForProfile(true, feed, hasRatedSongs = true))
        assertFalse(shouldRefreshHomeForProfile(false, feed, hasRatedSongs = true))
    }
}
