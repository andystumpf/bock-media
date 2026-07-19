package com.bockmedia.console.journeys

import android.Manifest
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.perf.BockUiHarness.typeSearchQuery
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.hasText
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
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
class SearchJourneyTest {
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

    @Test fun search_browse_empty() {
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SEARCH_FIELD)
        }
    }

    @Test fun search_query_unified() {
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.typeSearchQuery(SmokeTestConfig.searchQuery())
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.SEARCH_RESULTS) || composeRule.hasText("No results")
        }
    }
}
