package com.bockmedia.console.ui.util

fun parseTime24(time24: String): Pair<Int, Int> {
    val parts = time24.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
    val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return hour.coerceIn(0, 23) to minute.coerceIn(0, 59)
}

fun formatTime24(hour: Int, minute: Int): String =
    "${hour.coerceIn(0, 23).toString().padStart(2, '0')}:${minute.coerceIn(0, 59).toString().padStart(2, '0')}"

fun formatTime12(time24: String): String {
    val (hour24, minute) = parseTime24(time24)
    val ampm = if (hour24 < 12) "AM" else "PM"
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return "$hour12:${minute.toString().padStart(2, '0')} $ampm"
}
