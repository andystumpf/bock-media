package com.bockmedia.console.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bockmedia.console.domain.model.LibraryFilter
import com.bockmedia.console.domain.model.LibrarySort
import com.bockmedia.console.domain.model.LibraryViewMode
import kotlinx.coroutines.flow.first

private val Context.libraryPrefsStore by preferencesDataStore("library_prefs")

data class LibraryUiPrefs(
    val filter: LibraryFilter = LibraryFilter.All,
    val viewMode: LibraryViewMode = LibraryViewMode.Grid,
    val sort: LibrarySort = LibrarySort.Recents,
)

class LibraryPrefsStore(private val context: Context) {
    private val filterKey = stringPreferencesKey("filter")
    private val viewKey = stringPreferencesKey("view_mode")
    private val sortKey = stringPreferencesKey("sort")

    suspend fun loadSync(): LibraryUiPrefs {
        val data = context.libraryPrefsStore.data.first()
        return LibraryUiPrefs(
            filter = parseFilter(data[filterKey]),
            viewMode = parseViewMode(data[viewKey]),
            sort = parseSort(data[sortKey]),
        )
    }

    suspend fun save(
        filter: LibraryFilter,
        viewMode: LibraryViewMode,
        sort: LibrarySort,
        push: Boolean = true,
    ) {
        context.libraryPrefsStore.edit {
            it[filterKey] = filterTabValue(filter)
            it[viewKey] = if (viewMode == LibraryViewMode.Grid) "grid" else "list"
            it[sortKey] = if (sort == LibrarySort.Recents) "recents" else "name"
        }
        if (push) ClientPrefsSync.schedulePush(context)
    }

    suspend fun applyRemote(
        tab: String?,
        viewMode: String?,
        sortBy: String?,
        @Suppress("UNUSED_PARAMETER") sortOrder: String?,
    ) {
        val current = loadSync()
        save(
            filter = parseTab(tab) ?: current.filter,
            viewMode = parseViewModeRemote(viewMode) ?: current.viewMode,
            sort = parseSortRemote(sortBy) ?: current.sort,
            push = false,
        )
    }

    companion object {
        fun filterTabValue(filter: LibraryFilter): String = when (filter) {
            LibraryFilter.All -> "all"
            LibraryFilter.Playlists -> "playlists"
            LibraryFilter.Artists -> "artists"
            LibraryFilter.Albums -> "albums"
            LibraryFilter.Tracks -> "tracks"
            LibraryFilter.Downloaded -> "downloaded"
        }

        fun parseFilter(raw: String?): LibraryFilter = parseTab(raw) ?: LibraryFilter.All

        fun parseTab(raw: String?): LibraryFilter? = when (raw?.trim()?.lowercase()) {
            "all", "library" -> LibraryFilter.All
            "playlists" -> LibraryFilter.Playlists
            "artists" -> LibraryFilter.Artists
            "albums" -> LibraryFilter.Albums
            "tracks", "songs" -> LibraryFilter.Tracks
            "downloaded", "downloads" -> LibraryFilter.Downloaded
            else -> null
        }

        fun parseViewMode(raw: String?): LibraryViewMode =
            parseViewModeRemote(raw) ?: LibraryViewMode.Grid

        fun parseViewModeRemote(raw: String?): LibraryViewMode? = when (raw?.trim()?.lowercase()) {
            "grid" -> LibraryViewMode.Grid
            "list" -> LibraryViewMode.List
            else -> null
        }

        fun parseSort(raw: String?): LibrarySort =
            parseSortRemote(raw) ?: LibrarySort.Recents

        fun parseSortRemote(raw: String?): LibrarySort? = when (raw?.trim()?.lowercase()) {
            "recents", "recent" -> LibrarySort.Recents
            "name", "alphabetical" -> LibrarySort.Name
            else -> null
        }
    }
}
