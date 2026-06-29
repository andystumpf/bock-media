package com.bockmedia.console.perf

/** Soft budgets for instrumented UI perf audits (ms). Tune via `perfBudgetMultiplier` instrumentation arg. */
object PerfBudgets {
    const val WARM_LAUNCH_MAIN_MS = 5_000L
    const val COLD_START_MAIN_MS = 8_000L
    const val HOME_INTERACTIVE_MS = 6_000L
    const val TAB_SWITCH_MS = 1_200L
    const val TAB_REVISIT_MS = 400L
    const val SEARCH_RESULTS_MS = 8_000L
    const val LIBRARY_READY_MS = 6_000L
    const val AUTOMATIONS_READY_MS = 6_000L
    const val LOADING_SPINNER_MAX_MS = 4_000L
}
