package com.bockmedia.console.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bockmedia.console.perf.SpeedImprovementArea.COIL_INSTALL_RUNBLOCKING
import com.bockmedia.console.perf.SpeedImprovementArea.ENDPOINT_PROBE_MUTEX
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Documents all 20 speed-improvement areas for CI/report consumers.
 * Does not measure runtime — see [BockSpeedAuditTest] and [ColdStartPerfAuditTest].
 */
@RunWith(AndroidJUnit4::class)
class SpeedImprovementCatalogTest {

    @Test
    fun catalog_listsTwentyAreas() {
        val areas = SpeedImprovementArea.entries
        check(areas.size == 20) { "Expected 20 audit areas, found ${areas.size}" }
        areas.forEach { area ->
            check(area.auditHint.isNotBlank()) { "Area #${area.id} missing hint" }
        }
    }

    @Test
    fun catalog_coversStartupAndNetwork() {
        check(SpeedImprovementArea.entries.any { it == COIL_INSTALL_RUNBLOCKING })
        check(SpeedImprovementArea.entries.any { it == ENDPOINT_PROBE_MUTEX })
    }
}
