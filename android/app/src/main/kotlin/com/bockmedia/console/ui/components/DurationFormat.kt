package com.bockmedia.console.ui.components

fun formatTrackDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

fun formatAlbumDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hr, $minutes min"
        hours > 0 -> "$hours hr"
        minutes > 0 -> "$minutes min"
        else -> "< 1 min"
    }
}

fun formatAlbumSummary(trackCount: Int, totalSeconds: Int): String {
    val tracksLabel = if (trackCount == 1) "1 track" else "$trackCount tracks"
    return "$tracksLabel — ${formatAlbumDuration(totalSeconds)}"
}
