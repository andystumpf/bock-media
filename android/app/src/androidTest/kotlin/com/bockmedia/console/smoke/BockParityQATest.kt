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
import com.bockmedia.console.smoke.BockSmokeHarness.hasText
import com.bockmedia.console.smoke.BockSmokeHarness.navigateAccountRoute
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
import com.bockmedia.console.smoke.MobileTestHarness.openNowPlayingPreview
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Android parity QA — maps to docs/IOS_PARITY_PLAN.md §7 checklist. */
@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BockParityQATest {
    @get:Rule(order = 0)
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @get:Rule(order = 1)
    val composeRule = AndroidComposeTestRule(activityRule) { rule ->
        var activity: MainActivity? = null
        rule.scenario.onActivity { activity = it }
        checkNotNull(activity)
    }

    @get:Rule
    val grantNotifications = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun setUp() {
        MobileTestApi.teardownSweep(composeRule.activity)
        composeRule.assumeMainAppReady(SmokeTestConfig.defaultTimeoutMs())
        composeRule.ready()
    }

    @Test fun parity01_bottomNavShell() {
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.NAV_HOME)
        }
    }

    @Test fun parity02_homeFeedSections() {
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_FEED) || composeRule.hasTag(BockTestTags.HOME_GREETING)
        }
    }

    @Test fun parity03_searchBrowseDiscovery() {
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SEARCH_FIELD)
        }
        assertTrue(
            composeRule.hasText("Sonic explorations") ||
                composeRule.hasText("Sonic Adventure") ||
                composeRule.hasText("Edit shortcuts"),
        )
    }

    @Test fun parity04_libraryList() {
        composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.LIBRARY_LIST)
        }
    }

    @Test fun parity05_accountSettingsFamily() {
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SETTINGS_BODY)
        }
        composeRule.navigateAccountRoute("Family")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.FAMILY_BODY)
        }
    }

    @Test fun parity06_nowPlayingPreview() {
        composeRule.openNowPlayingPreview()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.NOW_PLAYING_BODY)
        }
    }

    @Test fun parity07_downloadsTab() {
        composeRule.tapBottomNav(BockTestTags.NAV_DOWNLOADS)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.DOWNLOADS_LIST)
        }
    }
}
