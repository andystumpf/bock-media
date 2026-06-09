package com.bockmedia.console.domain.model

import java.io.File

data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val localFile: File? = null,
) {
    val displayArtist: String get() = artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
}
