package com.bockmedia.console.perf

import android.util.Log
import org.junit.Assert.fail

data class PerfFinding(
    val scenario: String,
    val measuredMs: Long,
    val budgetMs: Long,
    val area: SpeedImprovementArea,
    val note: String = "",
) {
    val overBudget: Boolean get() = measuredMs > budgetMs
    val severity: String
        get() = when {
            measuredMs <= budgetMs -> "OK"
            measuredMs <= budgetMs * 2 -> "WARN"
            else -> "FAIL"
        }
}

object PerfAuditReport {
    const val LOG_TAG = "BockPerfAudit"

    private val findings = mutableListOf<PerfFinding>()

    fun clear() = findings.clear()

    fun record(
        scenario: String,
        measuredMs: Long,
        budgetMs: Long,
        area: SpeedImprovementArea,
        note: String = "",
    ) {
        val finding = PerfFinding(scenario, measuredMs, budgetMs, area, note)
        findings += finding
        Log.i(
            LOG_TAG,
            "${finding.severity} | $scenario | ${measuredMs}ms (budget ${budgetMs}ms) | #${area.id} ${area.name} | $note",
        )
    }

    fun allFindings(): List<PerfFinding> = findings.toList()

    fun printSummary() {
        val header = buildString {
            appendLine("=== Bock Media UI perf audit ===")
            appendLine("strict=${PerfAuditConfig.strict()} multiplier=${PerfAuditConfig.budgetMultiplier()}")
            appendLine("findings=${findings.size}")
        }
        Log.i(LOG_TAG, header)

        findings.forEach { f ->
            Log.i(
                LOG_TAG,
                "  [${f.severity}] ${f.scenario}: ${f.measuredMs}ms / ${f.budgetMs}ms → #${f.area.id} ${f.area.auditHint}",
            )
        }

        val over = findings.filter { it.overBudget }
        if (over.isNotEmpty()) {
            Log.w(LOG_TAG, "Over budget (${over.size}):")
            over.forEach { f ->
                Log.w(LOG_TAG, "  ${f.scenario}: +${f.measuredMs - f.budgetMs}ms → ${f.area.auditHint}")
            }
        } else {
            Log.i(LOG_TAG, "All scenarios within budget.")
        }
        Log.i(LOG_TAG, "=== end audit ===")
    }

    fun assertIfStrict() {
        printSummary()
        if (!PerfAuditConfig.strict()) return
        val failures = findings.filter { it.severity == "FAIL" }
        if (failures.isNotEmpty()) {
            fail(
                failures.joinToString("\n") { f ->
                    "${f.scenario}: ${f.measuredMs}ms > ${f.budgetMs}ms — ${f.area.auditHint}"
                },
            )
        }
    }
}
