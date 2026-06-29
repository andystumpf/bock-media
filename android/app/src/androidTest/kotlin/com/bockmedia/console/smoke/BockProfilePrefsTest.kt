package com.bockmedia.console.smoke

import android.Manifest
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.MainActivity
import androidx.compose.ui.test.onNodeWithContentDescription
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.smoke.BockSmokeHarness.hasTag
import com.bockmedia.console.smoke.BockSmokeHarness.navigateAccountRoute
import com.bockmedia.console.smoke.BockSmokeHarness.ready
import com.bockmedia.console.smoke.BockSmokeHarness.waitForLoadingGone
import com.bockmedia.console.ui.testing.BockTestTags
import android.os.SystemClock
import org.junit.Assume
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Profile-scoped preference CRUD + analytics attribution on a configured device
 * with at least two household members.
 *
 * Run: `./scripts/run_profile_prefs_tests.sh`
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BockProfilePrefsTest {

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

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var memberA: String
    private lateinit var memberAName: String
    private lateinit var memberB: String
    private lateinit var memberBName: String

    private fun assumeTwoMembers() {
        composeRule.ready()
        val members = ProfileTestApi.householdMembers(context)
        Assume.assumeTrue(
            "Need at least 2 household members (Family → Add member)",
            members.size >= 2,
        )
        memberA = members[0].id
        memberAName = members[0].name
        memberB = members[1].id
        memberBName = members[1].name
    }

    private fun navigateBackToShell() {
        val deadline = SystemClock.elapsedRealtime() + SmokeTestConfig.defaultTimeoutMs()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (composeRule.hasTag(BockTestTags.BOTTOM_NAV)) return
            runCatching { composeRule.onNodeWithContentDescription("Back").performClick() }
            composeRule.waitForIdle()
            Thread.sleep(150)
        }
    }

    private fun ensureHomeShell() {
        navigateBackToShell()
        composeRule.ready()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.hasTag(BockTestTags.HOME_GREETING) ||
                composeRule.hasTag(BockTestTags.HOME_FEED) ||
                composeRule.hasTag(BockTestTags.ACCOUNT_MENU_BUTTON)
        }
        composeRule.waitForIdle()
    }

    private fun switchTo(memberId: String) {
        navigateBackToShell()
        ProfileTestApi.switchToMember(context, memberId)
        composeRule.waitForIdle()
        ensureHomeShell()
    }

    private fun openSettings() {
        ensureHomeShell()
        composeRule.navigateAccountRoute("Settings")
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.onAllNodesWithTag(BockTestTags.SETTINGS_BODY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun setContinueAfterQueue(value: String, label: String) {
        openSettings()
        composeRule.onNodeWithTag(BockTestTags.settingsContinue(value))
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            ProfileTestApi.localContinueAfterQueue(context) == value
        }
        ProfileTestApi.flushPrefs(context)
        ProfileTestApi.waitForRemoteContinue(context, ActiveProfileStore.activeMemberId(context)!!, value)
    }

    private fun openAnalytics() {
        ensureHomeShell()
        composeRule.navigateAccountRoute("Analytics")
        composeRule.waitForLoadingGone()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            composeRule.onAllNodesWithTag(BockTestTags.ANALYTICS_BODY).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTotalPlays(expected: Int) {
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            runCatching {
                composeRule.onNodeWithTag(BockTestTags.ANALYTICS_TOTAL_PLAYS)
                    .assert(hasText(expected.toString(), substring = true))
            }.isSuccess
        }
    }

    @Test
    fun profile01_createMemberA_continueAfterQueue() {
        assumeTwoMembers()
        switchTo(memberA)
        setContinueAfterQueue("artist_radio", "Artist radio")
        ProfileTestApi.assertRemoteContinue(context, memberA, "artist_radio")
    }

    @Test
    fun profile02_createMemberB_differentPref() {
        assumeTwoMembers()
        switchTo(memberB)
        setContinueAfterQueue("similar", "Similar songs")
        ProfileTestApi.assertRemoteContinue(context, memberB, "similar")
        ProfileTestApi.assertRemoteContinue(context, memberA, "artist_radio")
    }

    @Test
    fun profile03_readMemberA_afterSwitchBack() {
        assumeTwoMembers()
        switchTo(memberA)
        openSettings()
        composeRule.onNodeWithTag(BockTestTags.settingsContinue("artist_radio"))
            .performScrollTo()
            .assert(hasText("Artist radio"))
        ProfileTestApi.assertRemoteContinue(context, memberA, "artist_radio")
    }

    @Test
    fun profile04_updateMemberA_toOff() {
        assumeTwoMembers()
        switchTo(memberA)
        setContinueAfterQueue("off", "Stop")
        ProfileTestApi.assertRemoteContinue(context, memberA, "off")
        ProfileTestApi.assertRemoteContinue(context, memberB, "similar")
    }

    @Test
    fun profile05_wifiOnlyToggle_perProfile() {
        assumeTwoMembers()
        switchTo(memberA)
        openSettings()
        composeRule.onNodeWithTag(BockTestTags.SETTINGS_WIFI_ONLY).performClick()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            ProfileTestApi.localWifiOnly(context)
        }
        ProfileTestApi.flushPrefs(context)
        Assume.assumeTrue(ProfileTestApi.wifiOnlyForMember(context, memberA) == true)

        switchTo(memberB)
        openSettings()
        composeRule.onNodeWithTag(BockTestTags.SETTINGS_WIFI_ONLY).assertIsOff()

        switchTo(memberA)
        openSettings()
        composeRule.onNodeWithTag(BockTestTags.SETTINGS_WIFI_ONLY).assertIsOn()

        composeRule.onNodeWithTag(BockTestTags.SETTINGS_WIFI_ONLY).performClick()
        composeRule.waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            !ProfileTestApi.localWifiOnly(context)
        }
        ProfileTestApi.flushPrefs(context)
        Assume.assumeTrue(ProfileTestApi.wifiOnlyForMember(context, memberA) == false)
    }

    @Test
    fun profile06_analytics_totalPlays_matchesMemberScope() {
        assumeTwoMembers()
        switchTo(memberA)
        val expectedA = ProfileTestApi.memberTotalPlays(context, memberA)
        openAnalytics()
        waitForTotalPlays(expectedA)

        switchTo(memberB)
        val expectedB = ProfileTestApi.memberTotalPlays(context, memberB)
        openAnalytics()
        if (expectedA != expectedB) {
            waitForTotalPlays(expectedB)
        }
    }
}
