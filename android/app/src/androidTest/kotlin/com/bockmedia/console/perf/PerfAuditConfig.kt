package com.bockmedia.console.perf

import androidx.test.platform.app.InstrumentationRegistry

object PerfAuditConfig {
    private fun args() = InstrumentationRegistry.getArguments()

    /** When true, budget violations fail the test run. Default: report-only. */
    fun strict(): Boolean = args().getString("perfStrict", "false") == "true"

    /** Scale all budgets (e.g. 2.0 on slow CI hardware). */
    fun budgetMultiplier(): Double =
        args().getString("perfBudgetMultiplier", "1.0")?.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 1.0

    fun scaledBudget(baseMs: Long): Long = (baseMs * budgetMultiplier()).toLong()

    /** Search query used in perf tests (≥2 chars). Override via instrumentation arg. */
    fun searchQuery(): String = args().getString("perfSearchQuery", "ab") ?: "ab"
}
