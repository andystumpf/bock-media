package com.bockmedia.console.perf

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.assumeMainAppReady
import com.bockmedia.console.perf.BockUiHarness.assertHomeOrLoadingVisible
import com.bockmedia.console.perf.BockUiHarness.hasTag
import com.bockmedia.console.perf.BockUiHarness.measureUntilSoft
import com.bockmedia.console.perf.BockUiHarness.tapBottomNav
import com.bockmedia.console.perf.BockUiHarness.typeSearchQuery
import com.bockmedia.console.perf.BockUiHarness.waitForTagGone
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BockSpeedAuditTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val grantNotifications = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
    )

    companion object {
        @JvmStatic
        @BeforeClass
        fun resetReport() {
            PerfAuditReport.clear()
        }
    }

    private fun audit(
        scenario: String,
        budgetMs: Long,
        area: SpeedImprovementArea,
        timeoutMs: Long = budgetMs + 5_000,
        note: String = "",
        block: () -> Unit = {},
        ready: () -> Boolean,
    ) {
        val (ms, ok) = composeRule.measureUntilSoft(timeoutMs, block, ready)
        PerfAuditReport.record(
            scenario = scenario,
            measuredMs = ms,
            budgetMs = budgetMs,
            area = area,
            note = if (ok) note else "timeout — $note".trim(),
        )
    }

    @Test
    fun a01_warmLaunch_mainNavVisible() {
        val budget = PerfAuditConfig.scaledBudget(PerfBudgets.WARM_LAUNCH_MAIN_MS)
        val start = SystemClock.elapsedRealtime()
        composeRule.assumeMainAppReady()
        val ms = SystemClock.elapsedRealtime() - start
        PerfAuditReport.record(
            scenario = "warm_launch_bottom_nav",
            measuredMs = ms,
            budgetMs = budget,
            area = SpeedImprovementArea.COLD_START_RUNBLOCKING,
            note = "Activity launched by ComposeTestRule",
        )
    }

    @Test
    fun a02_homeInteractive() {
        composeRule.assumeMainAppReady()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        val budget = PerfAuditConfig.scaledBudget(PerfBudgets.HOME_INTERACTIVE_MS)
        audit(
            scenario = "home_interactive",
            budgetMs = budget,
            area = SpeedImprovementArea.HOME_SEVEN_CALL_BURST,
            ready = {
                composeRule.hasTag(BockTestTags.HOME_GREETING) ||
                    composeRule.hasTag(BockTestTags.HOME_FEED) ||
                    composeRule.hasTag(BockTestTags.SCREEN_LOADING)
            },
        )
        composeRule.assertHomeOrLoadingVisible()
        val spinnerGone = composeRule.waitForTagGone(
            BockTestTags.SCREEN_LOADING,
            PerfAuditConfig.scaledBudget(PerfBudgets.LOADING_SPINNER_MAX_MS),
        )
        if (!spinnerGone) {
            PerfAuditReport.record(
                scenario = "home_spinner_clear",
                measuredMs = PerfAuditConfig.scaledBudget(PerfBudgets.LOADING_SPINNER_MAX_MS),
                budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.LOADING_SPINNER_MAX_MS),
                area = SpeedImprovementArea.HOME_SEVEN_CALL_BURST,
                note = "spinner still visible after budget",
            )
        }
    }

    @Test
    fun b01_tabSwitch_search() {
        composeRule.assumeMainAppReady()
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        audit(
            scenario = "tab_switch_search",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.TAB_SWITCH_MS),
            area = SpeedImprovementArea.TAB_PREFETCH_STORM,
            timeoutMs = PerfBudgets.TAB_SWITCH_MS + 3_000,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_SEARCH) },
            ready = { composeRule.hasTag(BockTestTags.SEARCH_FIELD) },
        )
    }

    @Test
    fun b02_tabSwitch_library() {
        composeRule.assumeMainAppReady()
        audit(
            scenario = "tab_switch_library",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.TAB_SWITCH_MS),
            area = SpeedImprovementArea.TRIPLE_PLAYLIST_FETCH,
            timeoutMs = PerfBudgets.TAB_SWITCH_MS + 3_000,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY) },
            ready = {
                composeRule.hasTag(BockTestTags.LIBRARY_LIST) ||
                    !composeRule.hasTag(BockTestTags.SCREEN_LOADING)
            },
        )
    }

    @Test
    fun b03_tabSwitch_automations() {
        composeRule.assumeMainAppReady()
        audit(
            scenario = "tab_switch_automations",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.TAB_SWITCH_MS),
            area = SpeedImprovementArea.AUTOMATION_RELOAD,
            timeoutMs = PerfBudgets.TAB_SWITCH_MS + 3_000,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_AUTOMATIONS) },
            ready = {
                composeRule.hasTag(BockTestTags.SCREEN_LOADING) ||
                    composeRule.hasTag(BockTestTags.AUTOMATIONS_CONTENT)
            },
        )
    }

    @Test
    fun c01_tabRevisit_home() {
        composeRule.assumeMainAppReady()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        audit(
            scenario = "tab_revisit_home",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.TAB_REVISIT_MS),
            area = SpeedImprovementArea.TAB_PREFETCH_STORM,
            timeoutMs = PerfBudgets.TAB_REVISIT_MS + 2_000,
            note = "Search→Home round-trip",
            block = {
                composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
                composeRule.tapBottomNav(BockTestTags.NAV_HOME)
            },
            ready = {
                composeRule.hasTag(BockTestTags.HOME_GREETING) ||
                    composeRule.hasTag(BockTestTags.HOME_FEED)
            },
        )
    }

    @Test
    fun c02_tabRevisit_search() {
        composeRule.assumeMainAppReady()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        composeRule.tapBottomNav(BockTestTags.NAV_HOME)
        audit(
            scenario = "tab_revisit_search",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.TAB_REVISIT_MS),
            area = SpeedImprovementArea.SEARCH_BROWSE_RELOAD,
            timeoutMs = PerfBudgets.TAB_REVISIT_MS + 2_000,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_SEARCH) },
            ready = { composeRule.hasTag(BockTestTags.SEARCH_FIELD) },
        )
    }

    @Test
    fun d01_searchTypeResults() {
        composeRule.assumeMainAppReady()
        composeRule.tapBottomNav(BockTestTags.NAV_SEARCH)
        val query = PerfAuditConfig.searchQuery()
        audit(
            scenario = "search_results_$query",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.SEARCH_RESULTS_MS),
            area = SpeedImprovementArea.SEARCH_ART_N_PLUS_1,
            note = "includes 250ms debounce",
            block = { composeRule.typeSearchQuery(query) },
            ready = {
                !composeRule.hasTag(BockTestTags.SCREEN_LOADING) &&
                    composeRule.hasTag(BockTestTags.SEARCH_RESULTS)
            },
        )
    }

    @Test
    fun e01_libraryContentReady() {
        composeRule.assumeMainAppReady()
        audit(
            scenario = "library_content_ready",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.LIBRARY_READY_MS),
            area = SpeedImprovementArea.LIBRARY_RESORT_PREFETCH,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_LIBRARY) },
            ready = { !composeRule.hasTag(BockTestTags.SCREEN_LOADING) },
        )
    }

    @Test
    fun e02_automationsContentReady() {
        composeRule.assumeMainAppReady()
        audit(
            scenario = "automations_content_ready",
            budgetMs = PerfAuditConfig.scaledBudget(PerfBudgets.AUTOMATIONS_READY_MS),
            area = SpeedImprovementArea.AUTOMATION_RELOAD,
            block = { composeRule.tapBottomNav(BockTestTags.NAV_AUTOMATIONS) },
            ready = {
                composeRule.hasTag(BockTestTags.AUTOMATIONS_CONTENT) ||
                    !composeRule.hasTag(BockTestTags.SCREEN_LOADING)
            },
        )
    }

    @Test
    fun z99_emitAuditSummary() {
        PerfAuditReport.assertIfStrict()
    }
}
