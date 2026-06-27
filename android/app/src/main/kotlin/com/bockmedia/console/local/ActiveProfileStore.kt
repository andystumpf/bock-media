package com.bockmedia.console.local

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The household member this install is "acting as" — used to attribute plays,
 *  send messages, share playlists, and approve requests. */
object ActiveProfileStore {
    private const val PREFS = "bock_client"
    private const val KEY = "active_member_id"

    private val _activeMemberId = MutableStateFlow<String?>(null)
    val activeMemberIdState: StateFlow<String?> = _activeMemberId.asStateFlow()

    fun hydrate(context: Context) {
        if (_activeMemberId.value != null) return
        _activeMemberId.value = readPrefs(context)
    }

    fun activeMemberId(context: Context): String? {
        hydrate(context)
        return _activeMemberId.value
    }

    fun setActiveMember(context: Context, id: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val normalized = id?.trim()?.takeIf { it.isNotBlank() }
        if (normalized.isNullOrBlank()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, normalized).apply()
        _activeMemberId.value = normalized
    }

    private fun readPrefs(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }
}
