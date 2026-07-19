package com.bockmedia.console.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackCrossfadePolicyTest {
    @Test
    fun blocksCrossfadeWhenBackgrounded() {
        assertFalse(
            LocalPlaybackCrossfadePolicy.mayStartCrossfade(
                crossfadeMs = 3000,
                crossfading = false,
                isPlaying = true,
                inForeground = false,
            ),
        )
    }

    @Test
    fun allowsCrossfadeWhenForeground() {
        assertTrue(
            LocalPlaybackCrossfadePolicy.mayStartCrossfade(
                crossfadeMs = 3000,
                crossfading = false,
                isPlaying = true,
                inForeground = true,
            ),
        )
    }
}
