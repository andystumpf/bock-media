package com.bockmedia.console.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.searchHistoryStore by preferencesDataStore("search_history")

class SearchHistoryStore(private val context: Context) {
    private val keyHistory = stringPreferencesKey("queries_csv")
    private val maxItems = 10

    val queries: Flow<List<String>> = context.searchHistoryStore.data.map { prefs ->
        prefs[keyHistory]
            ?.split('\u0001')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    suspend fun queriesSync(): List<String> = queries.first()

    suspend fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val current = queriesSync().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        save(current.take(maxItems))
    }

    suspend fun remove(query: String) {
        val current = queriesSync().filterNot { it.equals(query, ignoreCase = true) }
        save(current)
    }

    suspend fun clear() {
        save(emptyList())
    }

    private suspend fun save(items: List<String>) {
        context.searchHistoryStore.edit { prefs ->
            if (items.isEmpty()) prefs.remove(keyHistory)
            else prefs[keyHistory] = items.joinToString("\u0001")
        }
    }
}
