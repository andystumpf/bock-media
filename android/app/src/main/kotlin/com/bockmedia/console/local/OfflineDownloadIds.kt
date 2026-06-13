package com.bockmedia.console.local

import com.bockmedia.console.domain.model.PlayTarget
import java.net.URLEncoder

fun PlayTarget.downloadId(): String = when (this) {
    is PlayTarget.Playlist -> "pl-$id"
    is PlayTarget.Artist -> "artist-${slug(name)}"
    is PlayTarget.Album -> "album-${slug(name)}-${slug(artist ?: "")}"
    is PlayTarget.Song -> "song-${slug(path)}"
    is PlayTarget.Radio -> when (seedKind) {
        PlayTarget.RadioSeedKind.Song -> "radio-song-${slug(path ?: name)}"
        PlayTarget.RadioSeedKind.Artist -> "radio-artist-${slug(name)}"
        PlayTarget.RadioSeedKind.Genre -> "radio-genre-${slug(name)}"
    }
}

fun PlayTarget.downloadKindLabel(): String = when (this) {
    is PlayTarget.Playlist -> "Playlist"
    is PlayTarget.Artist -> "Artist"
    is PlayTarget.Album -> "Album"
    is PlayTarget.Song -> "Song"
    is PlayTarget.Radio -> "Mix"
}

private fun slug(raw: String): String {
    val trimmed = raw.trim().take(120)
    return URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
}
