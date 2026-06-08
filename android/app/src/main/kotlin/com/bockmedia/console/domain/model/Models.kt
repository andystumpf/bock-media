package com.bockmedia.console.domain.model

sealed class PlayTarget {
    abstract val label: String
    data class Playlist(val id: String, val name: String) : PlayTarget() { override val label = name }
    data class Artist(val name: String) : PlayTarget() { override val label = name }
    data class Album(val name: String, val artist: String? = null) : PlayTarget() { override val label = name }
    data class Song(val path: String, val title: String) : PlayTarget() { override val label = title }
}

data class NowPlayingProgress(
    val elapsedMs: Long,
    val durationMs: Long,
) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

fun computeNowPlayingProgress(timestamp: Double?, durationMs: Long, offsetMs: Long, paused: Boolean): NowPlayingProgress {
    if (timestamp == null || durationMs <= 0) return NowPlayingProgress(0, durationMs)
    val elapsedSec = if (paused) 0.0 else (System.currentTimeMillis() / 1000.0) - timestamp
    val posMs = offsetMs + (elapsedSec * 1000).toLong()
    return NowPlayingProgress(posMs.coerceAtMost(durationMs), durationMs)
}

fun formatPlaybackTime(seconds: Long): String {
    val sec = seconds.coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

fun buildRoutinePhrase(playlist: String, shuffle: Boolean, alias: String = "bock media"): String {
    val verb = if (shuffle) "mix" else "start"
    return "Alexa, ask $alias to $verb the $playlist playlist"
}
