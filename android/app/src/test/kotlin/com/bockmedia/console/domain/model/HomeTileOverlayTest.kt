package com.bockmedia.console.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTileOverlayTest {

    private fun playlistCard(id: String = "p1") = HomeCard(
        id = "pl-$id",
        title = "Test",
        playTarget = PlayTarget.Playlist(id, "Test"),
        kind = HomeSectionKind.RecentPlaylists,
    )

    private fun radioCard() = HomeCard(
        id = "radio-1",
        title = "Jazz Radio",
        playTarget = PlayTarget.Radio("Jazz Radio", PlayTarget.RadioSeedKind.Genre, "Jazz"),
        kind = HomeSectionKind.Radio,
    )

    @Test
    fun playlistTilesHideDownloadOverlay() {
        assertFalse(playlistCard().showsHomeDownloadOverlay(HomeSectionKind.RecentPlaylists, "recent-playlists"))
    }

    @Test
    fun radioTilesHidePlayAndDownloadOnRadioSection() {
        assertFalse(radioCard().showsHomePlayOverlay(HomeSectionKind.Radio, "radio"))
        assertFalse(radioCard().showsHomeDownloadOverlay(HomeSectionKind.Radio, "radio"))
    }

    @Test
    fun genreBrowseTilesHidePlayAndDownloadForPlaylists() {
        val genreCard = HomeCard(
            id = "genre-rock",
            title = "Rock",
            playTarget = PlayTarget.Radio("Rock", PlayTarget.RadioSeedKind.Genre, "Rock"),
            kind = HomeSectionKind.BrowseGenres,
        )
        assertFalse(genreCard.showsHomePlayOverlay(HomeSectionKind.BrowseGenres, "browse-genres"))
        assertFalse(genreCard.showsHomeDownloadOverlay(HomeSectionKind.BrowseGenres, "browse-genres"))

        val pl = playlistCard()
        assertFalse(pl.showsHomePlayOverlay(HomeSectionKind.RecentlyCreated, "recently-created"))
        assertFalse(pl.showsHomeDownloadOverlay(HomeSectionKind.RecentlyCreated, "recently-created"))
    }

    @Test
    fun followedReleasesHidePlayAndDownloadOverlays() {
        val albumCard = HomeCard(
            id = "album-x",
            title = "New Album",
            playTarget = PlayTarget.Album("New Album", "Artist"),
            kind = HomeSectionKind.Discover,
        )
        assertFalse(albumCard.showsHomePlayOverlay(HomeSectionKind.Discover, "followed-releases"))
        assertFalse(albumCard.showsHomeDownloadOverlay(HomeSectionKind.Discover, "followed-releases"))
        assertTrue(albumCard.showsHomePlayOverlay(HomeSectionKind.Discover, "discover-weekly"))
    }
}
