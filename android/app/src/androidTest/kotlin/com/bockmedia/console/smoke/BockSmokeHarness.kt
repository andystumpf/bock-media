package com.bockmedia.console.smoke

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.bockmedia.console.MainActivity
import com.bockmedia.console.perf.BockUiHarness.assumeMainAppReady
import com.bockmedia.console.perf.BockUiHarness.waitForTagGone
import com.bockmedia.console.ui.testing.BockTestTags
import org.junit.Assert.assertTrue

object BockSmokeHarness {
    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.ready() {
        assumeMainAppReady(SmokeTestConfig.defaultTimeoutMs())
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openAccountMenu() {
        waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            hasTag(BockTestTags.ACCOUNT_MENU_BUTTON) ||
                runCatching {
                    onAllNodesWithContentDescription("Account menu").fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
        }
        runCatching { onNodeWithTag(BockTestTags.ACCOUNT_MENU_BUTTON).performClick() }
            .onFailure { onNodeWithContentDescription("Account menu").performClick() }
        waitForIdle()
        waitUntil(SmokeTestConfig.defaultTimeoutMs()) {
            hasTag(BockTestTags.ACCOUNT_MENU)
        }
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.navigateAccountRoute(
        title: String,
    ) {
        openAccountMenu()
        onNodeWithText(title).performClick()
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.clearSearchField() {
        runCatching {
            onNodeWithTag(BockTestTags.SEARCH_FIELD).performTextReplacement("")
        }.onFailure {
            onNodeWithText("Search…").performTextReplacement("")
        }
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForLoadingGone(
        timeoutMs: Long = SmokeTestConfig.defaultTimeoutMs(),
    ): Boolean = waitForTagGone(BockTestTags.SCREEN_LOADING, timeoutMs)

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.hasTag(
        tag: String,
    ): Boolean = runCatching {
        onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.hasText(
        text: String,
    ): Boolean = runCatching {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assertAnyTag(
        vararg tags: String,
        message: String,
    ) {
        assertTrue(message, tags.any { hasTag(it) })
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assertAnyText(
        vararg texts: String,
        message: String,
    ) {
        assertTrue(message, texts.any { hasText(it) })
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.waitForSearchResults(
        timeoutMs: Long = SmokeTestConfig.defaultTimeoutMs(),
    ) {
        waitUntil(timeoutMs) {
            hasTag(BockTestTags.SEARCH_RESULTS) && !hasTag(BockTestTags.SCREEN_LOADING)
        }
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.assertTabTitle(
        title: String,
        timeoutMs: Long = SmokeTestConfig.defaultTimeoutMs(),
    ) {
        waitUntil(timeoutMs) { hasTag(BockTestTags.TAB_HEADER) && hasText(title) }
        assertTrue(
            "Expected tab title $title",
            hasTag(BockTestTags.TAB_HEADER) && hasText(title),
        )
    }
}
