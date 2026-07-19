package com.bockmedia.console.smoke

import androidx.test.platform.app.InstrumentationRegistry

/** Instrumentation args for [BockDeviceSmokeTest] on a connected device. */
object SmokeTestConfig {
    private fun args() = InstrumentationRegistry.getArguments()

    fun defaultTimeoutMs(): Long =
        args().getString("smokeTimeoutMs", "45000")?.toLongOrNull()?.coerceAtLeast(5_000) ?: 45_000

    /** Search query for results smoke (≥2 chars). Override: `-e smokeSearchQuery rock` */
    fun searchQuery(): String = args().getString("smokeSearchQuery", "love") ?: "love"

    fun shortSearchQuery(): String = args().getString("smokeShortSearchQuery", "ab") ?: "ab"
}
