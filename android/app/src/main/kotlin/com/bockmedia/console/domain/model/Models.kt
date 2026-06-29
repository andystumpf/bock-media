package com.bockmedia.console.domain.model

sealed class PlayTarget {
    abstract val label: String
    open val shuffleDefault: Boolean get() = false

    data class Playlist(val id: String, val name: String, val shuffle: Boolean = false) : PlayTarget() {
        override val label = name
        override val shuffleDefault: Boolean get() = shuffle
    }
    data class Artist(val name: String) : PlayTarget() { override val label = name }
    data class Album(val name: String, val artist: String? = null, val shuffle: Boolean = false) : PlayTarget() {
        override val label = name
        override val shuffleDefault: Boolean get() = shuffle
    }
    data class Song(val path: String, val title: String) : PlayTarget() { override val label = title }

    enum class RadioSeedKind { Artist, Song, Genre }

    /** Infinite-style radio: shuffle from a single artist, song, or genre seed. */
    data class Radio(
        val displayTitle: String,
        val seedKind: RadioSeedKind,
        val name: String,
        val path: String? = null,
    ) : PlayTarget() {
        override val label: String = displayTitle
        override val shuffleDefault: Boolean = true

        /** Genre behind a mix/radio tile (display title), not the artist seed in [name]. */
        fun genreLabel(): String? {
            if (seedKind != RadioSeedKind.Genre) return null
            return HomeFeedRules.mixGenreLabel(displayTitle)
                ?: HomeFeedRules.genreRadioLabel(displayTitle)
                ?: name.takeIf { it.isNotBlank() }
        }
    }
}

data class LocalPlayContext(
    val sourceLabel: String,
    val playlist: String? = null,
    val playlistId: String? = null,
)

fun PlayTarget.toLocalPlayContext(): LocalPlayContext = when (this) {
    is PlayTarget.Playlist -> LocalPlayContext(
        sourceLabel = "Playlist · $name",
        playlist = name,
        playlistId = id.takeIf { it.isNotBlank() },
    )
    is PlayTarget.Artist -> LocalPlayContext(sourceLabel = "Artist · $name")
    is PlayTarget.Album -> LocalPlayContext(
        sourceLabel = listOfNotNull("Album", name).joinToString(" · "),
    )
    is PlayTarget.Song -> LocalPlayContext(sourceLabel = "Song")
    is PlayTarget.Radio -> LocalPlayContext(sourceLabel = "Mix · $displayTitle")
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
