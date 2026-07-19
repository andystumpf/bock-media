package com.bockmedia.console.journeys

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.smoke.MobileTestHarness.openRoute
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.hasText
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
import com.bockmedia.console.smoke.MobileTestApi
import com.bockmedia.console.smoke.MobileTestHarness.openDeepLink
import com.bockmedia.console.smoke.MobileTestHarness.resetShell
import com.bockmedia.console.smoke.SmokeTestConfig
import com.bockmedia.console.smoke.VersionConsistencyTest
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResilienceJourneyTest {
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
        composeRule.ready()
    }

    @Test fun resilience_server_health() {
        MobileTestApi.assertHealthWatchdog(composeRule.activity)
    }

    @Test fun resilience_playlist_detail_after_preflight() {
        val id = assumeNotNull(MobileTestApi.resolveFixturePlaylistId(composeRule.activity))
        composeRule.openRoute("playlists/detail/$id")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.PLAYLIST_DETAIL_BODY)
        }
    }

    @Test fun resilience_home_recovers_after_tab_switch() {
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitForLoadingGone()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_FEED) || composeRule.hasTag(BockTestTags.HOME_GREETING)
        }
    }

    @Test fun resilience_injected_home_api_500() {
        MobileTestApi.assertInjectedHomeFailure(composeRule.activity)
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.openDeepLink("bockmedia://uitest/fail?endpoint=home")
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_FEED) ||
                composeRule.hasTag(BockTestTags.HOME_GREETING) ||
                composeRule.hasText("Could not load home") ||
                composeRule.hasText("Retry")
        }
        composeRule.openDeepLink("bockmedia://uitest/clear-fail")
    }

    @Test fun resilience_version_consistency() {
        VersionConsistencyTest().versionMatchesReleaseNotes()
    }

    @Test fun resilience_reset_shell_deeplink() {
        composeRule.resetShell()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.NAV_HOME)
        }
    }
}
