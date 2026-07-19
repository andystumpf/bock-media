package com.bockmedia.console.smoke

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.assumeMainAppReady
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.navigateAccountRoute
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.ui.navigation.BockRoute
import com.bockmedia.console.ui.navigation.albumsArtistRoute
import com.bockmedia.console.ui.navigation.genreRoute
import com.bockmedia.console.ui.navigation.songsAlbumRoute
import com.bockmedia.console.ui.testing.BockTestTags
import com.bockmedia.console.smoke.MobileTestHarness.openNowPlayingPreview
import com.bockmedia.console.smoke.MobileTestHarness.openRoute
import com.bockmedia.console.smoke.MobileTestHarness.resetShell
import com.bockmedia.console.smoke.MobileUiQuality.enableAccessibilityChecks
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Tier 2 — visit major routes and assert screen body tags (catalog nav.* workflows). */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BockNavigationAuditTest {
    init {
        enableAccessibilityChecks()
    }

    @get:Rule(order = 0)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule(order = 1)
    val composeRule = AndroidComposeTestRule(activityRule) { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity) { "MainActivity not ready" }
    }

    @get:Rule
    val grantNotifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun setUp() {
        MobileTestApi.teardownSweep(composeRule.activity)
        composeRule.resetShell()
        composeRule.assumeMainAppReady(SmokeTestConfig.defaultTimeoutMs())
    }

    private fun prepare() {
        composeRule.resetShell()
        composeRule.ready()
    }

    private fun prepareForRoute() {
        // Destination reset/navigation happens in openRoute(); avoid main-shell gate here
        // (prior test may leave Now Playing or an account screen without bottom nav).
        composeRule.waitForIdle()
    }

    @Test fun nav01_home() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_FEED) || composeRule.hasTag(BockTestTags.HOME_GREETING)
        }
    }

    @Test fun nav02_search() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SEARCH_FIELD)
        }
    }

    @Test fun nav03_library() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.LIBRARY_LIST)
        }
    }

    @Test fun nav04_downloads_tab() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_DOWNLOADS)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.DOWNLOADS_LIST)
        }
    }

    @Test fun nav05_automations() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_AUTOMATIONS)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.AUTOMATIONS_CONTENT)
        }
    }

    @Test fun nav06_settings() {
        prepare()
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SETTINGS_BODY)
        }
    }

    @Test fun nav07_analytics() {
        prepare()
        composeRule.navigateAccountRoute("Analytics")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ANALYTICS_BODY)
        }
    }

    @Test fun nav08_family() {
        prepare()
        composeRule.navigateAccountRoute("Family")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.FAMILY_BODY)
        }
    }

    @Test fun nav09_devices() {
        prepare()
        composeRule.navigateAccountRoute("Alexa Devices")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.DEVICES_BODY)
        }
    }

    @Test fun nav10_rooms() {
        prepare()
        composeRule.navigateAccountRoute("Rooms")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ROOMS_BODY)
        }
    }

    @Test fun nav11_voice_log() {
        prepare()
        composeRule.navigateAccountRoute("Voice log")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.VOICE_LOG_BODY)
        }
    }

    @Test fun nav12_driving() {
        prepare()
        composeRule.navigateAccountRoute("Driving Mode")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.DRIVING_BODY)
        }
    }

    @Test fun nav13_about() {
        prepare()
        composeRule.navigateAccountRoute("About")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ABOUT_BODY)
        }
    }

    @Test fun nav14_playlist_detail() {
        prepareForRoute()
        val id = assumeNotNull(MobileTestApi.resolveFixturePlaylistId(composeRule.activity))
        composeRule.openRoute("playlists/detail/$id")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.PLAYLIST_DETAIL_BODY)
        }
    }

    @Test fun nav15_now_playing_preview() {
        prepare()
        composeRule.openNowPlayingPreview()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.NOW_PLAYING_BODY)
        }
    }

    @Test fun nav16_routines_deeplink() {
        prepareForRoute()
        composeRule.openRoute(BockRoute.Routines.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ROUTINES_BODY)
        }
    }

    @Test fun nav17_favorites() {
        prepareForRoute()
        openRoute(BockRoute.Favorites.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.FAVORITES_BODY)
        }
    }

    @Test fun nav18_playlists_list() {
        prepareForRoute()
        openRoute(BockRoute.Playlists.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.PLAYLISTS_LIST_BODY)
        }
    }

    @Test fun nav19_artists_list() {
        prepareForRoute()
        openRoute(BockRoute.Artists.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ARTISTS_LIST_BODY)
        }
    }

    @Test fun nav20_albums_list() {
        prepareForRoute()
        openRoute(BockRoute.Albums.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ALBUMS_LIST_BODY)
        }
    }

    @Test fun nav21_songs_list() {
        prepareForRoute()
        openRoute(BockRoute.Songs.route)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SONGS_LIST_BODY)
        }
    }

    @Test fun nav22_genre_detail() {
        prepareForRoute()
        openRoute(genreRoute(MobileTestApi.fixtureGenreName()))
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.GENRE_DETAIL_BODY)
        }
    }

    @Test fun nav23_artist_detail() {
        prepareForRoute()
        val artist = MobileTestApi.fixtureArtistName()
        openRoute(albumsArtistRoute(artist))
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ARTIST_DETAIL_BODY)
        }
    }

    @Test fun nav24_album_detail() {
        prepareForRoute()
        val album = MobileTestApi.fixtureAlbumTitle()
        val artist = MobileTestApi.fixtureAlbumArtist()
        openRoute(songsAlbumRoute(album, artist))
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.ALBUM_DETAIL_BODY)
        }
    }

    @Test fun nav25_search_browse() {
        prepare()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SEARCH_FIELD)
        }
    }

    private fun openRoute(route: String) {
        composeRule.openRoute(route)
    }
}
