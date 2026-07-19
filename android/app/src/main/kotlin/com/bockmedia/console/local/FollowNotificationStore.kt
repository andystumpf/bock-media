package com.bockmedia.console.local

import android.content.Context

/** Per-profile cursor for followed-artist new-music notifications. */
object FollowNotificationStore {
    private const val PREFS = "follow_notifications"

    private fun key(memberId: String) = "last_seen_${memberId.ifBlank { "default" }}"

    fun lastSeenAt(context: Context, memberId: String?): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(key(memberId.orEmpty()), null)?.takeIf { it.isNotBlank() }
    }

    fun markSeen(context: Context, memberId: String?, timestamp: String?) {
        val ts = timestamp?.trim().orEmpty()
        if (ts.isEmpty()) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key(memberId.orEmpty()), ts)
            .apply()
    }

    fun markSeenFromItems(context: Context, memberId: String?, timestamps: List<String?>) {
        val latest = timestamps.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.maxOrNull()
        markSeen(context, memberId, latest)
    }
}
