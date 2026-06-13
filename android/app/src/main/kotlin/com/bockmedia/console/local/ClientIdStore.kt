package com.bockmedia.console.local

import android.content.Context
import java.util.UUID

/** Stable install identity for per-device analytics. */
object ClientIdStore {
    private const val PREFS = "bock_client"
    private const val KEY = "client_id"

    fun clientId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
