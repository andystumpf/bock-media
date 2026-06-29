package com.bockmedia.console.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.local.ClientPrefsSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.searchHistoryStore by preferencesDataStore("search_history")

class SearchHistoryStore(private val context: Context) {
    private val keySelections = stringPreferencesKey("selections_json")
    private val maxItems = 12
    private val listSerializer = ListSerializer(SearchRecentSelection.serializer())

    val selections: Flow<List<SearchRecentSelection>> = context.searchHistoryStore.data.map { prefs ->
        decode(prefs[keySelections])
    }

    /** @deprecated Query strings — use [selections] and [addSelection]. */
    val queries: Flow<List<String>> = selections.map { items ->
        items.map { it.title }
    }

    suspend fun selectionsSync(): List<SearchRecentSelection> = selections.first()

    suspend fun addSelection(selection: SearchRecentSelection) {
        val trimmed = selection.title.trim()
        if (trimmed.isEmpty()) return
        val current = selectionsSync().toMutableList()
        current.removeAll { it.key == selection.key }
        current.add(0, selection)
        save(current.take(maxItems))
    }

    suspend fun replaceSelections(items: List<SearchRecentSelection>) {
        save(items.take(maxItems))
    }

    suspend fun removeSelection(selection: SearchRecentSelection) {
        save(selectionsSync().filterNot { it.key == selection.key })
    }

    suspend fun clearSelections() {
        save(emptyList())
    }

    suspend fun remove(query: String) {
        save(selectionsSync().filterNot { it.title.equals(query, ignoreCase = true) })
    }

    suspend fun clear() = clearSelections()

    private suspend fun save(items: List<SearchRecentSelection>) {
        context.searchHistoryStore.edit { prefs ->
            if (items.isEmpty()) prefs.remove(keySelections)
            else prefs[keySelections] = bockJson.encodeToString(listSerializer, items)
        }
        ClientPrefsSync.schedulePush(context)
    }

    private fun decode(raw: String?): List<SearchRecentSelection> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            bockJson.decodeFromString(listSerializer, raw)
        }.getOrDefault(emptyList())
    }
}
