package com.bockmedia.console.ui.analytics

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
}
