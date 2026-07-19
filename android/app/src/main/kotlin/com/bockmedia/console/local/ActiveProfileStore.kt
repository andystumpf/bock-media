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
    private const val KEY_PROFILE_CHOSEN = "profile_choice_made"

    private val _activeMemberId = MutableStateFlow<String?>(null)
    val activeMemberIdState: StateFlow<String?> = _activeMemberId.asStateFlow()

    private val _profileChoiceMade = MutableStateFlow(false)
    val profileChoiceMadeState: StateFlow<Boolean> = _profileChoiceMade.asStateFlow()

    private var hydrated = false

    fun hydrate(context: Context) {
        if (hydrated) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedMember = prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }
        var chosen = prefs.getBoolean(KEY_PROFILE_CHOSEN, false)
        // Upgrades: a saved member implies the user already picked a profile.
        if (!chosen && !storedMember.isNullOrBlank()) {
            chosen = true
            prefs.edit().putBoolean(KEY_PROFILE_CHOSEN, true).apply()
        }
        _activeMemberId.value = storedMember
        _profileChoiceMade.value = chosen
        hydrated = true
    }

    fun activeMemberId(context: Context): String? {
        hydrate(context)
        return _activeMemberId.value
    }

    fun hasProfileChoice(context: Context): Boolean {
        hydrate(context)
        return _profileChoiceMade.value
    }

    fun setActiveMember(context: Context, id: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val normalized = id?.trim()?.takeIf { it.isNotBlank() }
        if (normalized.isNullOrBlank()) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, normalized).apply()
        _activeMemberId.value = normalized
        markProfileChosen(context)
    }

    /** Clears a member id that no longer exists on the server; keeps profile-choice flag. */
    fun clearStaleMember(context: Context) {
        hydrate(context)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
        _activeMemberId.value = null
    }

    /** User explicitly chose to stay unattributed (first-run picker or Family dropdown). */
    fun chooseUnattributed(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
        _activeMemberId.value = null
        markProfileChosen(context)
    }

    private fun markProfileChosen(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROFILE_CHOSEN, true)
            .apply()
        _profileChoiceMade.value = true
    }
}
