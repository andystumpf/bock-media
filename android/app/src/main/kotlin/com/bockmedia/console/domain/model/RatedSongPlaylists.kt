package com.bockmedia.console.domain.model

/** Virtual playlists backed by GET /api/playlists/rated-stars-N */
object RatedSongPlaylists {
    fun id(stars: Int): String = "rated-stars-${stars.coerceIn(1, 5)}"

    fun title(stars: Int): String {
        val n = stars.coerceIn(1, 5)
        return "$n★ songs"
    }

    fun playTarget(stars: Int): PlayTarget = PlayTarget.Playlist(id(stars), title(stars))

    val starLevelsDescending: List<Int> = listOf(5, 4, 3, 2, 1)
}
