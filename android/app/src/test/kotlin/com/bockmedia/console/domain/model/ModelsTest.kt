package com.bockmedia.console.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {

    @Test
    fun computeNowPlayingProgress_atStart() {
        val now = System.currentTimeMillis() / 1000.0
        val p = computeNowPlayingProgress(now, 180_000, 0, false)
        assertEquals(180_000, p.durationMs)
        assert(p.elapsedMs >= 0)
    }

    @Test
    fun computeNowPlayingProgress_paused() {
        val p = computeNowPlayingProgress(System.currentTimeMillis() / 1000.0, 60_000, 10_000, true)
        assertEquals(10_000, p.elapsedMs)
    }

    @Test
    fun buildRoutinePhrase_shuffleUsesMix() {
        assertEquals(
            "Alexa, ask bock media to mix the yacht rock playlist",
            buildRoutinePhrase("yacht rock", shuffle = true),
        )
        assertEquals(
            "Alexa, ask bock media to start the daily music playlist",
            buildRoutinePhrase("daily music", shuffle = false),
        )
    }
}
