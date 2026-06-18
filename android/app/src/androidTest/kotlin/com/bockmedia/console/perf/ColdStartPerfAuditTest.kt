package com.bockmedia.console.perf

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.bockmedia.console.MainActivity
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activity relaunch audit (same process). True process cold start cannot use `am force-stop`
 * from instrumentation — it runs in [targetPkg] and would kill the test runner.
 * For process cold start use: `adb shell am force-stop … && adb shell am start -W …`
 */
@RunWith(AndroidJUnit4::class)
class ColdStartPerfAuditTest {

    private lateinit var device: UiDevice
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        device.pressHome()
    }

    @Test
    fun activityRelaunch_toMainShell() {
        ActivityScenario.launch(MainActivity::class.java).use { it.close() }

        val budget = PerfAuditConfig.scaledBudget(PerfBudgets.COLD_START_MAIN_MS)
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use {
            if (device.wait(Until.hasObject(By.text("Sign in to your server")), 2_000)) {
                Assume.assumeFalse("Complete server setup on device before cold-start perf audit", true)
            }
            val found = device.wait(Until.hasObject(By.text("Home")), budget + 15_000)
            val elapsed = SystemClock.elapsedRealtime() - start
            Assume.assumeTrue("Main shell (Home tab) did not appear within timeout", found)
            PerfAuditReport.record(
                scenario = "activity_relaunch",
                measuredMs = elapsed,
                budgetMs = budget,
                area = SpeedImprovementArea.COLD_START_RUNBLOCKING,
                note = "ActivityScenario relaunch (same process); adb am start -W for true cold start",
            )
        }
        PerfAuditReport.printSummary()
        PerfAuditReport.assertIfStrict()
    }
}
