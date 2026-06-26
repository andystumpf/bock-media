package com.bockmedia.console.domain.model

import com.bockmedia.console.domain.model.PlayTarget.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardBrowseTest {
    @Test
    fun destination_playlistFromLinkedId() {
        val card = HomeCard(
            id = "pl-1",
            title = "Rock Mix",
            playlistId = "abc",
            playTarget = Playlist("abc", "Rock Mix"),
            kind = HomeSectionKind.TopMixes,
        )
        assertEquals(
            HomeCardBrowse.Destination.Playlist("abc"),
            HomeCardBrowse.destination(card),
        )
    }

    @Test
    fun destination_artistCardOpensArtist() {
        // A genuine artist tile (not a synthetic "<genre> Mix") opens the artist.
        val card = HomeCard(
            id = "jump-artist",
            title = "Led Zeppelin",
            playTarget = PlayTarget.Artist("Led Zeppelin"),
            kind = HomeSectionKind.JumpBackIn,
        )
        assertEquals(
            HomeCardBrowse.Destination.Artist("Led Zeppelin"),
            HomeCardBrowse.destination(card),
        )
    }

    @Test
    fun destination_syntheticGenreMixOpensGenreNotArtist() {
        val card = HomeCard(
            id = "mix-Rock",
            title = "Rock Mix",
            playTarget = PlayTarget.Artist("Led Zeppelin"),
            kind = HomeSectionKind.TopMixes,
        )
        assertEquals(
            HomeCardBrowse.Destination.Genre("Rock"),
            HomeCardBrowse.destination(card),
        )
    }

    @Test
    fun destination_syntheticGenreMixWithRadioOpensGenre() {
        val card = HomeCard(
            id = "mix-Rock",
            title = "Rock Mix",
            playTarget = PlayTarget.Radio("Rock Mix", PlayTarget.RadioSeedKind.Genre, "Led Zeppelin"),
            kind = HomeSectionKind.TopMixes,
        )
        assertEquals(
            HomeCardBrowse.Destination.Genre("Rock"),
            HomeCardBrowse.destination(card),
        )
    }

    @Test
    fun destination_radioGenreOpensGenre() {
        val card = HomeCard(
            id = "radio-jazz",
            title = "Jazz Radio",
            playTarget = PlayTarget.Radio("Jazz Radio", PlayTarget.RadioSeedKind.Genre, "Jazz"),
            kind = HomeSectionKind.Radio,
        )
        assertTrue(HomeCardBrowse.destination(card) is HomeCardBrowse.Destination.Genre)
    }
}
