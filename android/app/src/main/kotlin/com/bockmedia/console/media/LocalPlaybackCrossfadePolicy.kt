package com.bockmedia.console.media

/** Crossfade gating — skip overlap when backgrounded (Android 12+ FGS limits). */
object LocalPlaybackCrossfadePolicy {
    fun mayStartCrossfade(
        crossfadeMs: Long,
        crossfading: Boolean,
        isPlaying: Boolean,
        inForeground: Boolean,
    ): Boolean {
        if (crossfadeMs <= 0 || crossfading || !isPlaying) return false
        return inForeground
    }
}
