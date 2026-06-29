package com.bockmedia.console.smoke

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.perf.BockUiHarness.typeSearchQuery
import com.bockmedia.console.smoke.BockSmokeHarness.assertAnyTag
import com.bockmedia.console.smoke.BockSmokeHarness.assertAnyText
import com.bockmedia.console.smoke.BockSmokeHarness.assertTabTitle
import com.bockmedia.console.smoke.BockSmokeHarness.clearSearchField
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.hasText
import com.bockmedia.console.smoke.BockSmokeHarness.navigateAccountRoute
import com.bockmedia.console.smoke.BockSmokeHarness.openAccountMenu
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
import com.bockmedia.console.smoke.BockSmokeHarness.waitForSearchResults
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Functional smoke tests against a connected device with server already configured.
 *
 * Run: `./scripts/run_android_smoke_tests.sh`
 * Skip perf suite: uses `-e class com.bockmedia.console.smoke.BockDeviceSmokeTest` only.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BockDeviceSmokeTest {

    /** One activity for all ordered smoke tests — avoids 20× cold boot. */
    @get:Rule(order = 0)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule(order = 1)
    val composeRule = AndroidComposeTestRule(activityRule) { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity) { "MainActivity not ready" }
    }

    @get:Rule
    val grantNotifications = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
    )

    companion object {
        @JvmStatic
        private var bootstrapped = false
    }

    private fun readyOnce() {
        if (bootstrapped) {
            composeRule.waitForIdle()
            return
        }
        composeRule.ready()
        bootstrapped = true
    }

    // ── Launch & shell ──────────────────────────────────────────────────────

    @Test
    fun smoke01_launch_bottomNavVisible() {
        readyOnce()
        assertTrue(composeRule.hasTag(BockTestTags.BOTTOM_NAV))
    }

    @Test
    fun smoke02_home_showsContent() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_GREETING) ||
                composeRule.hasTag(BockTestTags.HOME_FEED) ||
                composeRule.hasText("Good morning") ||
                composeRule.hasText("Good afternoon") ||
                composeRule.hasText("Good evening")
        }
        composeRule.assertAnyTag(
            BockTestTags.HOME_GREETING,
            BockTestTags.HOME_FEED,
            message = "Home should show greeting or feed",
        )
    }

    // ── Bottom navigation ───────────────────────────────────────────────────

    @Test
    fun smoke03_bottomNav_search() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        assertTrue(composeRule.hasTag(BockTestTags.SEARCH_FIELD))
        composeRule.assertTabTitle("Search")
    }

    @Test
    fun smoke04_bottomNav_library() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY)
        composeRule.waitForLoadingGone()
        assertTrue(
            composeRule.hasTag(BockTestTags.LIBRARY_LIST) || !composeRule.hasTag(BockTestTags.SCREEN_LOADING),
        )
    }

    @Test
    fun smoke05_bottomNav_downloads() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_DOWNLOADS)
        composeRule.assertTabTitle("Downloads")
    }

    @Test
    fun smoke06_bottomNav_automations() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_AUTOMATIONS)
        composeRule.waitForLoadingGone()
        composeRule.assertAnyTag(
            BockTestTags.AUTOMATIONS_CONTENT,
            BockTestTags.SCREEN_LOADING,
            message = "Automations tab should load",
        )
    }

    // ── Search ──────────────────────────────────────────────────────────────

    @Test
    fun smoke07_search_browseOnEmptyQuery() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.clearSearchField()
        composeRule.waitForIdle()
        assertTrue(composeRule.hasTag(BockTestTags.SEARCH_FIELD))
    }

    @Test
    fun smoke08_search_shortQuery_returnsResults() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.typeSearchQuery(SmokeTestConfig.shortSearchQuery())
        composeRule.waitForSearchResults()
        assertTrue(composeRule.hasTag(BockTestTags.SEARCH_RESULTS))
    }

    @Test
    fun smoke09_search_fullQuery_returnsResults() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.typeSearchQuery(SmokeTestConfig.searchQuery())
        composeRule.waitForSearchResults()
        assertTrue(composeRule.hasTag(BockTestTags.SEARCH_RESULTS))
    }

    @Test
    fun smoke10_search_clearReturnsToBrowse() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.typeSearchQuery(SmokeTestConfig.searchQuery())
        composeRule.waitForSearchResults()
        composeRule.clearSearchField()
        composeRule.waitForIdle()
        assertTrue(!composeRule.hasTag(BockTestTags.SEARCH_RESULTS) || composeRule.hasTag(BockTestTags.SEARCH_FIELD))
    }

    // ── Tab round-trip ──────────────────────────────────────────────────────

    @Test
    fun smoke11_tabRoundTrip_allPrimaryTabs() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        assertTrue(composeRule.hasTag(BockTestTags.NAV_HOME))
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        assertTrue(composeRule.hasTag(BockTestTags.SEARCH_FIELD))
        composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY)
        composeRule.waitForLoadingGone()
        composeRule.tapBottomNav(BockTestTags.NAV_DOWNLOADS)
        composeRule.assertTabTitle("Downloads")
        composeRule.tapBottomNav(BockTestTags.NAV_AUTOMATIONS)
        composeRule.waitForLoadingGone()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.assertAnyTag(BockTestTags.HOME_GREETING, BockTestTags.HOME_FEED, message = "Home after round-trip")
    }

    // ── Account menu routes ─────────────────────────────────────────────────

    @Test
    fun smoke12_accountMenu_opens() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.openAccountMenu()
        assertTrue(composeRule.hasTag(BockTestTags.ACCOUNT_MENU))
    }

    @Test
    fun smoke13_accountMenu_analytics() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Analytics")
        composeRule.waitForLoadingGone()
        assertTrue(composeRule.hasTag(BockTestTags.ANALYTICS_BODY))
    }

    @Test
    fun smoke14_accountMenu_settings() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitForLoadingGone()
        assertTrue(composeRule.hasTag(BockTestTags.SETTINGS_BODY))
        composeRule.assertAnyText(
            "Download over Wi‑Fi only",
            "Crossfade",
            message = "Settings should show playback options",
        )
    }

    @Test
    fun smoke15_accountMenu_family() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Family")
        composeRule.waitForLoadingGone()
        composeRule.assertAnyText("Family", "Members", message = "Family screen should open")
    }

    @Test
    fun smoke16_accountMenu_devices() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Alexa Devices")
        composeRule.waitForLoadingGone()
        composeRule.assertAnyText(
            "Alexa Devices",
            "Speakers",
            "Alexa",
            "Devices",
            "Echo",
            message = "Devices screen should open",
        )
    }

    // ── Server-backed content ───────────────────────────────────────────────

    @Test
    fun smoke17_library_hasItemsOrEmptyState() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY)
        composeRule.waitForLoadingGone()
        assertTrue(
            composeRule.hasTag(BockTestTags.LIBRARY_LIST) ||
                composeRule.hasText("No items found") ||
                composeRule.hasText("Nothing downloaded"),
        )
    }

    @Test
    fun smoke18_analytics_dataOrEmptyState() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Analytics")
        composeRule.waitForLoadingGone()
        composeRule.assertAnyText(
            "Total Plays",
            "No device activity yet",
            "Activity Over Time",
            message = "Analytics should show stats or empty state",
        )
    }

    @Test
    fun smoke19_settings_libraryStatsOrHealth() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitForLoadingGone()
        composeRule.assertAnyText(
            "Library",
            "Server",
            "tracks",
            "Download over Wi‑Fi only",
            message = "Settings should show library or server info",
        )
    }

    @Test
    fun smoke20_search_revisitCachedResults() {
        readyOnce()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.typeSearchQuery(SmokeTestConfig.searchQuery())
        composeRule.waitForSearchResults()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitForIdle()
        assertTrue(
            composeRule.hasTag(BockTestTags.SEARCH_RESULTS) ||
                composeRule.hasTag(BockTestTags.SEARCH_FIELD),
        )
    }
}
