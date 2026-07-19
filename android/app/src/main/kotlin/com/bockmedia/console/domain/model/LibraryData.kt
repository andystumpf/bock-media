package com.bockmedia.console.domain.model

/** Library rows grouped by kind — load once, filter locally without extra API calls. */
data class LibraryData(
    val playlists: List<LibraryItem> = emptyList(),
    val artists: List<LibraryItem> = emptyList(),
    val albums: List<LibraryItem> = emptyList(),
    val offline: List<LibraryItem> = emptyList(),
) {
    fun forFilter(filter: LibraryFilter): List<LibraryItem> = when (filter) {
        LibraryFilter.All -> playlists + offline
        LibraryFilter.Playlists -> playlists
        LibraryFilter.Artists -> artists
        LibraryFilter.Albums -> albums
        LibraryFilter.Tracks -> emptyList()
        LibraryFilter.Downloaded -> offline
    }
}
