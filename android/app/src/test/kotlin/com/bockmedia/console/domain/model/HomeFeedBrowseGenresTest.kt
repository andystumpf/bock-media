package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.GenreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeFeedBrowseGenresTest {
    @Test
    fun compose_browseGenresSection_fromLibraryGenres() {
        val genres = listOf(
            GenreItem(name = "Rock", tracks = 120, artPath = "/rock.mp3"),
            GenreItem(name = "Jazz", tracks = 80, artPath = "/jazz.mp3"),
            GenreItem(name = "Tiny", tracks = 3),
        )
        val feed = HomeFeedComposer.compose(
            HomeFeedInput(
                history = emptyList(),
                analytics = null,
                allPlaylists = emptyList(),
                smartPlaylists = emptyList(),
                favorites = emptyList(),
                dashboard = null,
                libraryGenres = genres,
                shuffleSeed = 1L,
            ),
        )
        val section = feed.sections.firstOrNull { it.kind == HomeSectionKind.BrowseGenres }
        assertNotNull(section)
        assertEquals("Browse by genre", section!!.title)
        assertEquals(2, section.cards.size)
        assertEquals("Rock", section.cards[0].title)
    }
}
