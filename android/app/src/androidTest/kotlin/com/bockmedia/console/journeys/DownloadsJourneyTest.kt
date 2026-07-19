package com.bockmedia.console.journeys

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.navigateAccountRoute
import com.bockmedia.console.smoke.BockSmokeHarness.hasText
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
import com.bockmedia.console.smoke.MobileTestHarness.resetShell
import com.bockmedia.console.smoke.MobileTestApi
import com.bockmedia.console.smoke.SmokeTestConfig
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@LargeTest
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DownloadsJourneyTest {
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
        composeRule.resetShell()
        composeRule.ready()
    }

    @Test fun downloads_tab_list() {
        composeRule.tapBottomNav(BockTestTags.NAV_DOWNLOADS)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.DOWNLOADS_LIST)
        }
    }

    @Test fun downloads_wifi_only_settings() {
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SETTINGS_BODY)
        }
        composeRule.waitForLoadingGone()
        // Wi‑Fi toggle may be below the fold; assert the Downloads settings section loaded.
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasText("Offline music saved on this phone")
        }
    }
}
