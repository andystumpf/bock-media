package com.bockmedia.console.ui.analytics

import com.bockmedia.console.data.api.dto.ActivityPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AnalyticsDateRangeTest {
    private val today = LocalDate.of(2026, 6, 19)

    @Test
    fun last7Range_isInclusiveSevenDays() {
        val (from, to) = analyticsDateRange(DatePreset.Last7, null, null, today)
        assertEquals("2026-06-13", from)
        assertEquals("2026-06-19", to)
    }

    @Test
    fun last30Range_isInclusiveThirtyDays() {
        val (from, to) = analyticsDateRange(DatePreset.Last30, null, null, today)
        assertEquals("2026-05-21", from)
        assertEquals("2026-06-19", to)
    }

    @Test
    fun allTimeRange_hasNoBounds() {
        val (from, to) = analyticsDateRange(DatePreset.AllTime, null, null, today)
        assertNull(from)
        assertNull(to)
    }

    @Test
    fun customRange_usesSelectedDates() {
        val (from, to) = analyticsDateRange(
            DatePreset.Custom,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 2, 1),
            today,
        )
        assertEquals("2026-01-01", from)
        assertEquals("2026-02-01", to)
    }

    @Test
    fun rangeKey_includesDeviceFilter() {
        assertEquals(
            "last7|client-abc",
            analyticsRangeKey(DatePreset.Last7, null, null, "client-abc"),
        )
        assertEquals("all|all-devices", analyticsRangeKey(DatePreset.AllTime, null, null, null))
    }

    @Test
    fun deviceFilter_mapsToApiDeviceId() {
        assertNull(AnalyticsDeviceFilter.AllDevices.apiDeviceId("client-abc"))
        assertEquals("client-abc", AnalyticsDeviceFilter.ThisPhone.apiDeviceId("client-abc"))
        assertEquals(
            "amzn1.echo",
            AnalyticsDeviceFilter.Specific("amzn1.echo", "Kitchen").apiDeviceId("client-abc"),
        )
    }

    @Test
    fun trimActivityPoints_dropsLeadingZerosButKeepsOne() {
        val points = listOf(
            ActivityPoint("2026-06-01", 0),
            ActivityPoint("2026-06-02", 0),
            ActivityPoint("2026-06-03", 0),
            ActivityPoint("2026-06-04", 5),
            ActivityPoint("2026-06-05", 2),
        )
        val trimmed = trimActivityPoints(points, ActivityPeriod.Day)
        assertEquals(listOf("2026-06-03", "2026-06-04", "2026-06-05"), trimmed.map { it.label })
        assertEquals(listOf(0, 5, 2), trimmed.map { it.count })
    }

    @Test
    fun formatActivityLabel_dayMonthWeekYear() {
        assertEquals("6/13", formatActivityLabel("2026-06-13", ActivityPeriod.Day))
        assertEquals("26", formatActivityLabel("2026-W26", ActivityPeriod.Week))
        assertEquals("Jun", formatActivityLabel("2026-06", ActivityPeriod.Month))
        assertEquals("2026", formatActivityLabel("2026", ActivityPeriod.Year))
    }
}
