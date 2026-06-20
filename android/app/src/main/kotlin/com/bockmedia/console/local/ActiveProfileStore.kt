package com.bockmedia.console.local

import android.content.Context

/** The household member this install is "acting as" — used to attribute plays,
 *  send messages, share playlists, and approve requests. */
object ActiveProfileStore {
    private const val PREFS = "bock_client"
    private const val KEY = "active_member_id"

    fun activeMemberId(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun setActiveMember(context: Context, id: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (id.isNullOrBlank()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, id).apply()
    }
}
