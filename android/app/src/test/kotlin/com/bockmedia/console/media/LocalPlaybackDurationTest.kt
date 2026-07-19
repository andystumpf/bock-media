package com.bockmedia.console.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackDurationTest {

    @Test
    fun effectiveDuration_prefersLibraryWhenPlayerIsTooShort() {
        assertEquals(
            240_000L,
            LocalPlaybackDuration.effectiveDurationMs(playerDurationMs = 16_000L, libraryDurationMs = 240_000L),
        )
    }

    @Test
    fun effectiveDuration_usesPlayerWhenReasonable() {
        assertEquals(
            238_000L,
            LocalPlaybackDuration.effectiveDurationMs(playerDurationMs = 238_000L, libraryDurationMs = 240_000L),
        )
    }

    @Test
    fun effectiveDuration_fallsBackToLibraryWhenUnset() {
        assertEquals(
            180_000L,
            LocalPlaybackDuration.effectiveDurationMs(playerDurationMs = C.TIME_UNSET, libraryDurationMs = 180_000L),
        )
    }

    @Test
    fun needsStreamTranscode_detectsFlac() {
        assertTrue(LocalPlaybackDuration.needsStreamTranscode("/music/a/track.flac"))
        assertFalse(LocalPlaybackDuration.needsStreamTranscode("/music/a/track.mp3"))
    }

    @Test
    fun endedPrematurely_whenFarFromExpectedEnd() {
        assertTrue(LocalPlaybackDuration.endedPrematurely(positionMs = 12_000L, libraryDurationMs = 240_000L))
        assertFalse(LocalPlaybackDuration.endedPrematurely(positionMs = 200_000L, libraryDurationMs = 240_000L))
    }
}
