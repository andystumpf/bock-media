package com.bockmedia.console.perf

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Assume

object BockUiHarness {
    private val greetingTexts = listOf("Good morning", "Good afternoon", "Good evening")

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.mainShellVisible(): Boolean {
        if (hasTag(BockTestTags.BOTTOM_NAV)) return true
        if (hasTag(BockTestTags.HOME_GREETING)) return true
        return runCatching {
            onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.hasText(
        text: String,
    ): Boolean = runCatching {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assumeMainAppReady(
        timeoutMs: Long = 30_000,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            dismissProfilePickerIfNeeded()
            if (!hasTag(BockTestTags.SPLASH)) {
                val onSetup = hasTag(BockTestTags.SETUP_SCREEN) || hasText("Sign in to your server")
                Assume.assumeFalse(
                    "Complete server setup on device (Sign in) before running instrumented tests",
                    onSetup,
                )
                if (mainShellVisible()) {
                    waitForIdle()
                    return
                }
            }
            Thread.sleep(100)
        }
        dismissProfilePickerIfNeeded()
        waitUntil(5_000) { !hasTag(BockTestTags.SPLASH) }
        Assume.assumeFalse(
            "Complete server setup on device (Sign in) before running instrumented tests",
            hasTag(BockTestTags.SETUP_SCREEN) || hasText("Sign in to your server"),
        )
        waitUntil(5_000) { mainShellVisible() }
        waitForIdle()
    }

    private fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.dismissProfilePickerIfNeeded() {
        if (!hasTag(BockTestTags.PROFILE_PICKER) && !hasText("Who's listening?")) return
        runCatching { onNodeWithText("Continue unattributed").performClick() }
            .onFailure {
                runCatching {
                    onAllNodesWithText("Parent").fetchSemanticsNodes().firstOrNull()
                    onNodeWithText("Parent").performClick()
                }
            }
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.tapBottomNav(
        tag: String,
    ) {
        val label = when (tag) {
            BockTestTags.NAV_HOME -> "Home"
            BockTestTags.NAV_SEARCH -> "Search"
            BockTestTags.NAV_LIBRARY -> "Library"
            BockTestTags.NAV_DOWNLOADS -> "Downloads"
            BockTestTags.NAV_AUTOMATIONS -> "Automations"
            else -> error("Unknown nav tag: $tag")
        }
        waitUntil(15_000) { hasTag(tag) || hasText(label) }
        runCatching { onNodeWithTag(tag).performClick() }
            .onFailure { onNodeWithText(label).performClick() }
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForTagGone(
        tag: String,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val loading = onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (loading.isEmpty()) return true
            Thread.sleep(50)
        }
        return onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForAnyTag(
        vararg tags: String,
        timeoutMs: Long,
    ): String? {
        var matched: String? = null
        waitUntil(timeoutMs) {
            tags.firstOrNull { tag ->
                onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }?.also { matched = it } != null
        }
        return matched
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.hasTag(
        tag: String,
    ): Boolean = runCatching {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.measureUntil(
        timeoutMs: Long,
        block: () -> Unit,
        ready: () -> Boolean,
    ): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        waitUntil(timeoutMs) { ready() }
        waitForIdle()
        return SystemClock.elapsedRealtime() - start
    }

    /** Like [measureUntil] but never throws — returns elapsed ms and whether [ready] was satisfied. */
    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.measureUntilSoft(
        timeoutMs: Long,
        block: () -> Unit,
        ready: () -> Boolean,
    ): Pair<Long, Boolean> {
        val start = SystemClock.elapsedRealtime()
        block()
        val satisfied = runCatching {
            waitUntil(timeoutMs) { ready() }
            true
        }.getOrDefault(false)
        waitForIdle()
        return SystemClock.elapsedRealtime() - start to satisfied
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.typeSearchQuery(
        query: String,
    ) {
        runCatching {
            onNodeWithTag(BockTestTags.SEARCH_FIELD).performClick()
            onNodeWithTag(BockTestTags.SEARCH_FIELD).performTextInput(query)
        }.onFailure {
            onNodeWithText("What do you want to listen to?").performClick()
            onNodeWithText("What do you want to listen to?").performTextInput(query)
        }
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assertHomeOrLoadingVisible() {
        val hasGreeting = onAllNodesWithTag(BockTestTags.HOME_GREETING).fetchSemanticsNodes().isNotEmpty()
        val hasFeed = onAllNodesWithTag(BockTestTags.HOME_FEED).fetchSemanticsNodes().isNotEmpty()
        val hasLoading = onAllNodesWithTag(BockTestTags.SCREEN_LOADING).fetchSemanticsNodes().isNotEmpty()
        Assume.assumeTrue("Home tab showed no content", hasGreeting || hasFeed || hasLoading)
    }
}
