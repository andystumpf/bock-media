package com.bockmedia.console.media

import androidx.media3.common.C

/** Duration helpers for ExoPlayer /stream playback (player metadata is often wrong). */
object LocalPlaybackDuration {
    /** Extensions that require server-side transcode (see server.py TRANSCODE_EXTS). */
    private val TRANSCODE_EXTENSIONS = setOf("flac", "wma", "wav", "ogg", "aif", "aiff")

    fun needsStreamTranscode(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in TRANSCODE_EXTENSIONS
    }

    /**
     * Prefer library duration when ExoPlayer reports an implausibly short length
     * (common on /stream responses before the full file is parsed).
     */
    fun effectiveDurationMs(playerDurationMs: Long, libraryDurationMs: Long): Long {
        val library = libraryDurationMs.coerceAtLeast(0L)
        if (playerDurationMs == C.TIME_UNSET || playerDurationMs <= 0L) return library
        if (library > 0L && playerDurationMs < library / 2) return library
        return playerDurationMs
    }

    /** True when playback ended far earlier than the library says the track should run. */
    fun endedPrematurely(positionMs: Long, libraryDurationMs: Long): Boolean {
        val library = libraryDurationMs.coerceAtLeast(0L)
        if (library < 60_000L) return false
        return positionMs < library / 2
    }
}
