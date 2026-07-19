package com.bockmedia.console.smoke

import android.net.Uri
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.lifecycle.lifecycleScope
import com.bockmedia.console.MainActivity
import com.bockmedia.console.ui.testing.UITestSupport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

object MobileTestHarness {
    /** Apply uitest deep link in-process (avoids spawning a second MainActivity). */
    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openDeepLink(uri: String) {
        val latch = CountDownLatch(1)
        activityRule.scenario.onActivity { act ->
            act.lifecycleScope.launch {
                UITestSupport.handleUri(act.applicationContext, Uri.parse(uri))
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "UITest deeplink timed out: $uri" }
        waitForIdle()
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openRoute(route: String) {
        openDeepLink("bockmedia://uitest/route?path=${Uri.encode(route)}")
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.resetShell() {
        openDeepLink("bockmedia://uitest/reset-shell")
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openSearchDeepLink(query: String) {
        openDeepLink("bockmedia://uitest/search?q=${Uri.encode(query)}")
    }

    fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.openNowPlayingPreview() {
        openDeepLink("bockmedia://uitest/now-playing-preview")
    }
}
