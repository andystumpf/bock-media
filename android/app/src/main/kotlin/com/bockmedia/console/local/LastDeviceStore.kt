package com.bockmedia.console.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.lastDeviceStore by preferencesDataStore("last_device")

class LastDeviceStore(private val context: Context) {
    private val key = stringPreferencesKey("device_value")

    suspend fun lastDeviceSync(): String? =
        context.lastDeviceStore.data.map { it[key]?.takeIf { v -> v.isNotBlank() } }.first()

    suspend fun setLastDevice(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        context.lastDeviceStore.edit { it[key] = trimmed }
    }
}
