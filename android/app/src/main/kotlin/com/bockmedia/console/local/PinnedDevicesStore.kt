package com.bockmedia.console.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pinnedDevicesStore by preferencesDataStore("pinned_devices")

class PinnedDevicesStore(private val context: Context) {
    private val keyPinned = stringPreferencesKey("pinned_csv")

    val pinnedValues: Flow<List<String>> = context.pinnedDevicesStore.data.map { prefs ->
        prefs[keyPinned]
            ?.split('|')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    suspend fun pinnedValuesSync(): List<String> = pinnedValues.first()

    suspend fun setPinned(values: List<String>) {
        context.pinnedDevicesStore.edit { prefs ->
            if (values.isEmpty()) prefs.remove(keyPinned)
            else prefs[keyPinned] = values.joinToString("|")
        }
        ClientPrefsSync.schedulePush(context)
    }

    suspend fun toggle(value: String) {
        val current = pinnedValuesSync().toMutableList()
        if (current.contains(value)) current.remove(value) else current.add(0, value)
        setPinned(current.take(6))
    }
}
