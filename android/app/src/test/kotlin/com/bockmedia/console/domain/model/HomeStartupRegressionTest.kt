package com.bockmedia.console.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards against post-launch freeze and scroll jank regressions (v2.6.172–174). */
class HomeStartupRegressionTest {
    @Test
    fun shouldPaintFreshHomeFeed_firstPaintAlwaysReplaces() {
        assertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed = false, withinSkipReloadWindow = true))
        assertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed = false, withinSkipReloadWindow = false))
    }

    @Test
    fun shouldPaintFreshHomeFeed_visibleFeedWithinSkipWindow_staysSilent() {
        assertFalse(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed = true, withinSkipReloadWindow = true))
    }

    @Test
    fun shouldPaintFreshHomeFeed_visibleFeedOutsideSkipWindow_replaces() {
        assertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed = true, withinSkipReloadWindow = false))
    }

    @Test
    fun shouldReloadHomeForProfileSwitch_onlyOnActualSwitch() {
        assertFalse(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId = null, currentMemberId = "a"))
        assertFalse(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId = "a", currentMemberId = "a"))
        assertTrue(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId = "a", currentMemberId = "b"))
    }

    @Test
    fun shouldSkipResumePull_debouncesDuplicateResumePull() {
        val bootPullMs = 1_000_000L
        assertTrue(shouldSkipResumePull(bootPullMs, bootPullMs + 1_000, RESUME_PULL_DEBOUNCE_MS))
        assertTrue(shouldSkipResumePull(bootPullMs, bootPullMs + 4_999, RESUME_PULL_DEBOUNCE_MS))
        assertFalse(shouldSkipResumePull(bootPullMs, bootPullMs + 5_000, RESUME_PULL_DEBOUNCE_MS))
        assertFalse(shouldSkipResumePull(bootPullMs, bootPullMs + 10_000, RESUME_PULL_DEBOUNCE_MS))
    }

    @Test
    fun shouldSkipResumePull_neverBlocksWhenNoPriorPull() {
        assertFalse(shouldSkipResumePull(0L, 9_999_999L, RESUME_PULL_DEBOUNCE_MS))
    }

    @Test
    fun visibleCardsForWarm_capsSectionsAndCards() {
        val cards = (1..20).map { i ->
            HomeCard(
                id = "c-$i",
                title = "Card $i",
                playTarget = PlayTarget.Playlist("pl-$i", "Card $i"),
                kind = HomeSectionKind.RecentPlaylists,
            )
        }
        val feed = HomeFeed(
            sections = (1..8).map { s ->
                HomeSection(
                    id = "sec-$s",
                    title = "Section $s",
                    kind = HomeSectionKind.RecentPlaylists,
                    cards = cards,
                )
            },
        )
        val warm = HomeArtworkResolver.visibleCardsForWarm(feed)
        assertEquals(48, warm.size)
        assertEquals("c-1", warm.first().id)
        assertEquals("c-12", warm[11].id)
        assertEquals("c-1", warm[12].id) // second section starts at card 1 again
    }
}
