package com.bockmedia.console.local

import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.domain.model.SearchSuggestion
import com.bockmedia.console.domain.model.SearchSuggestionKind
import kotlinx.serialization.Serializable

@Serializable
data class SearchRecentSelection(
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val artist: String? = null,
    val path: String? = null,
    val id: String? = null,
) {
    val key: String get() = listOf(kind, id, path, title, artist).joinToString("|")

    companion object {
        fun fromArtist(name: String, albumCount: Int? = null, artPath: String? = null) =
            SearchRecentSelection(
                kind = "artist",
                title = name,
                subtitle = albumCount?.let { c -> if (c == 1) "1 album" else "$c albums" },
                path = artPath,
            )

        fun fromAlbum(name: String, artist: String?, artPath: String? = null) =
            SearchRecentSelection(
                kind = "album",
                title = name,
                subtitle = artist,
                artist = artist,
                path = artPath,
            )

        fun fromSong(title: String, artist: String?, path: String?) =
            SearchRecentSelection(
                kind = "song",
                title = title,
                subtitle = artist,
                artist = artist,
                path = path,
            )

        fun fromPlaylist(id: String, name: String) =
            SearchRecentSelection(kind = "playlist", title = name, id = id)

        fun fromGenre(name: String) =
            SearchRecentSelection(kind = "genre", title = name)

        fun fromHit(kind: String, hit: SearchHit): SearchRecentSelection? = when (kind) {
            "artist" -> {
                val name = hit.name ?: return null
                fromArtist(name, hit.albums, hit.path)
            }
            "album" -> {
                val name = hit.name ?: return null
                fromAlbum(name, hit.artist, hit.path)
            }
            "song" -> {
                val title = hit.title ?: hit.name ?: return null
                fromSong(title, hit.artist, hit.path)
            }
            "playlist" -> hit.id?.let { fromPlaylist(it, hit.name ?: "Playlist") }
            "genre" -> hit.name?.let { fromGenre(it) }
            else -> null
        }

        fun fromSuggestion(suggestion: SearchSuggestion) = when (suggestion.kind) {
            SearchSuggestionKind.Artist -> fromArtist(suggestion.title, artPath = suggestion.path)
            SearchSuggestionKind.Album -> fromAlbum(suggestion.title, suggestion.artist, suggestion.path)
            SearchSuggestionKind.Playlist -> suggestion.id?.let { fromPlaylist(it, suggestion.title) }
            SearchSuggestionKind.Song -> fromSong(suggestion.title, suggestion.artist, suggestion.path)
        }
    }
}
