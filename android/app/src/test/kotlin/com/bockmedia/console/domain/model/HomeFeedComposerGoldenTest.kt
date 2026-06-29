package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.SmartPlaylist
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Golden test: a shared JSON fixture (`shared/fixtures/home_feed/`) feeds both the
 * Android and iOS [HomeFeedComposer]. We assert the RNG- and section-order-independent
 * invariants that must hold identically on both platforms — scheduled automation
 * playlists never surface, shortcut tiles are playlist/mix-only, and card ids are unique.
 */
class HomeFeedComposerGoldenTest {

    @Serializable
    private data class GoldenInput(
        val history: List<StreamHistoryItem> = emptyList(),
        val analytics: AnalyticsResponse? = null,
        val allPlaylists: List<PlaylistSummary> = emptyList(),
        val smartPlaylists: List<SmartPlaylist> = emptyList(),
        val favorites: List<FavoriteItem> = emptyList(),
        val dashboard: DashboardQuickResponse? = null,
        val libraryGenres: List<GenreItem> = emptyList(),
        val shuffleSeed: Long = 0,
    )

    @Serializable
    private data class GoldenExpected(
        val forbiddenTitlePrefix: String = "",
        val maxShortcutTiles: Int = 6,
        val automationPlaylistName: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun fixture(name: String): File? {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val candidate = dir?.resolve("shared/fixtures/home_feed/$name")
            if (candidate != null && candidate.exists()) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Test
    fun homeFeedGolden() {
        val inputFile = fixture("input.json")
        val expectedFile = fixture("expected.json")
        assumeTrue("shared/fixtures/home_feed not found", inputFile != null && expectedFile != null)

        val g = json.decodeFromString(GoldenInput.serializer(), inputFile!!.readText())
        val expected = json.decodeFromString(GoldenExpected.serializer(), expectedFile!!.readText())

        // Sanity: the automation playlist is present in the input pool so exclusion is meaningful.
        assertTrue(g.allPlaylists.any { it.name == expected.automationPlaylistName })

        val feed = HomeFeedComposer.compose(
            HomeFeedInput(
                history = g.history,
                analytics = g.analytics,
                allPlaylists = g.allPlaylists,
                smartPlaylists = g.smartPlaylists,
                favorites = g.favorites,
                dashboard = g.dashboard,
                libraryGenres = g.libraryGenres,
                shuffleSeed = g.shuffleSeed,
            )
        )

        val allCards = feed.sections.flatMap { it.cards }
        assertTrue("composer produced an empty feed", allCards.isNotEmpty())

        // 1. Scheduled automation playlists never surface in any section.
        assertFalse(
            "automation playlist leaked into feed",
            allCards.any { it.title.startsWith(expected.forbiddenTitlePrefix) },
        )

        // 2. Card ids are globally unique (registry dedup).
        val ids = allCards.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // 3. Shortcut tiles: playlist/mix-only, capped, never album/song, never automation.
        val shortcuts = feed.homeShortcutCards(limit = expected.maxShortcutTiles)
        assertTrue(shortcuts.size <= expected.maxShortcutTiles)
        shortcuts.forEach { card ->
            assertFalse(
                "shortcut tile has album/song target: ${card.title}",
                card.playTarget is PlayTarget.Album || card.playTarget is PlayTarget.Song,
            )
            assertFalse(
                "shortcut tile is an automation playlist: ${card.title}",
                card.title.startsWith(expected.forbiddenTitlePrefix),
            )
        }
        assertEquals("duplicate shortcut tiles", shortcuts.size, shortcuts.map { it.id }.toSet().size)
    }
}
