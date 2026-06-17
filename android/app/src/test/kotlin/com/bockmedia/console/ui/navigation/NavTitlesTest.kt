package com.bockmedia.console.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTitlesTest {

    @Test
    fun resolveScreenHeader_usesNavArgsForArtistAlbumRoutes() {
        val header = resolveScreenHeader(
            route = ROUTE_SONGS_ALBUM,
            album = "Abbey Road",
        )
        assertEquals("Abbey Road", header.title)
        assertEquals(true, header.showBack)
    }

    @Test
    fun resolveScreenHeader_doesNotShowPlaceholderWhenArgsMissing() {
        val header = resolveScreenHeader(route = ROUTE_SONGS_ARTIST, artist = null)
        assertEquals("Songs", header.title)
    }

    @Test
    fun resolveScreenHeader_decodesEncodedArtistForAlbumsRoute() {
        val header = resolveScreenHeader(
            route = ROUTE_ALBUMS_ARTIST,
            artist = "AC%2FDC",
        )
        assertEquals("AC/DC", header.title)
    }

    @Test
    fun resolveScreenHeader_fallsBackToRouteSuffixForDirectNavigation() {
        val header = resolveScreenHeader(route = "songs/artist/The%20Beatles")
        assertEquals("The Beatles", header.title)
    }

    @Test
    fun resolveScreenHeader_hidesTitleForBottomNavTabs() {
        assertEquals("", resolveScreenHeader(route = BockRoute.Search.route).title)
        assertEquals("", resolveScreenHeader(route = BockRoute.Library.route).title)
        assertEquals("", resolveScreenHeader(route = BockRoute.Automations.route).title)
    }

    @Test
    fun isBottomNavRoot_trueForTabRoutes() {
        assertTrue(isBottomNavRoot(BockRoute.Home.route))
        assertTrue(isBottomNavRoot(BockRoute.Search.route))
        assertFalse(isBottomNavRoot("albums/artist/Beatles"))
    }

    @Test
    fun resolveScreenHeader_handlesGenreRouteWithoutCrash() {
        val header = resolveScreenHeader(route = "genre/Rock", genre = "Rock")
        assertEquals("Rock", header.title)
        assertEquals(true, header.showBack)
    }

    @Test
    fun resolveScreenHeader_unknownTopRouteDoesNotCrash() {
        val header = resolveScreenHeader(route = "unknown/sub")
        assertEquals("Bock Media", header.title)
        assertEquals(true, header.showBack)
    }
}
