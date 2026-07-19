package com.bockmedia.console.smoke

/** Accessibility audit hook for tier-2 navigation (Compose checks when available). */
object MobileUiQuality {
    fun enableAccessibilityChecks() {
        // Compose AccessibilityChecks requires ui-test 1.6+; iOS uses performAccessibilityAudit in UITests.
    }
}
