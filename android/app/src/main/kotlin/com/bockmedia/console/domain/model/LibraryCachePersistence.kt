package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.api.bockJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** Disk snapshot so the Library tab paints instantly on cold start. */
object LibraryCachePersistence {
    private const val FILE_NAME = "library_cache.json"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    /** Bump when bucket shape changes so stale playlist-only snapshots are discarded. */
    private const val CACHE_VERSION = 3

    @Serializable
    private data class PlayTargetDto(
        val type: String,
        val id: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val path: String? = null,
        val title: String? = null,
        val displayTitle: String? = null,
        val seedKind: String? = null,
    )

    @Serializable
    private data class ItemDto(
        val id: String,
        val title: String,
        val subtitle: String,
        val kind: String,
        val artPath: String? = null,
        val playlistId: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val sortDate: Long = 0L,
        val playTarget: PlayTargetDto,
    )

    @Serializable
    private data class SnapshotDto(
        val cacheVersion: Int = CACHE_VERSION,
        val savedAtMs: Long,
        val playlists: List<ItemDto> = emptyList(),
        val artists: List<ItemDto> = emptyList(),
        val albums: List<ItemDto> = emptyList(),
        val offline: List<ItemDto> = emptyList(),
    )

    suspend fun save(context: Context, data: LibraryData) {
        if (data.playlists.isEmpty() && data.artists.isEmpty() && data.albums.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = SnapshotDto(
                    cacheVersion = CACHE_VERSION,
                    savedAtMs = System.currentTimeMillis(),
                    playlists = data.playlists.map { it.toDto() },
                    artists = data.artists.map { it.toDto() },
                    albums = data.albums.map { it.toDto() },
                    offline = data.offline.map { it.toDto() },
                )
                val json = bockJson.encodeToString(dto)
                val target = file(context)
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
        }
    }

    suspend fun load(context: Context): LibraryData? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return@runCatching null
            val dto = bockJson.decodeFromString<SnapshotDto>(f.readText())
            if (dto.cacheVersion < CACHE_VERSION) return@runCatching null
            if (System.currentTimeMillis() - dto.savedAtMs > MAX_AGE_MS) return@runCatching null
            val library = LibraryData(
                playlists = dto.playlists.mapNotNull { it.toModel() },
                artists = dto.artists.mapNotNull { it.toModel() },
                albums = dto.albums.mapNotNull { it.toModel() },
                offline = dto.offline.mapNotNull { it.toModel() },
            )
            if (library.playlists.isEmpty() && library.artists.isEmpty() && library.albums.isEmpty()) {
                return@runCatching null
            }
            // Drop playlist-only snapshots saved before artists/albums were cached.
            if (library.artists.isEmpty() && library.albums.isEmpty() && library.playlists.isNotEmpty()) {
                return@runCatching null
            }
            library
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun LibraryItem.toDto() = ItemDto(
        id = id,
        title = title,
        subtitle = subtitle,
        kind = kind.name,
        artPath = artPath,
        playlistId = playlistId,
        artistName = artistName,
        albumName = albumName,
        sortDate = sortDate,
        playTarget = playTarget.toDto(),
    )

    private fun PlayTarget.toDto(): PlayTargetDto = when (this) {
        is PlayTarget.Playlist -> PlayTargetDto("playlist", id = id, name = name)
        is PlayTarget.Artist -> PlayTargetDto("artist", name = name)
        is PlayTarget.Album -> PlayTargetDto("album", name = name, artist = artist)
        is PlayTarget.Song -> PlayTargetDto("song", path = path, title = title)
        is PlayTarget.Radio -> PlayTargetDto(
            "radio",
            displayTitle = displayTitle,
            name = name,
            path = path,
            seedKind = seedKind.name,
        )
    }

    private fun ItemDto.toModel(): LibraryItem? {
        val itemKind = runCatching { LibraryItemKind.valueOf(kind) }.getOrNull() ?: return null
        val target = playTarget.toModel() ?: return null
        return LibraryItem(
            id = id,
            title = title,
            subtitle = subtitle,
            kind = itemKind,
            playTarget = target,
            artPath = artPath,
            playlistId = playlistId,
            artistName = artistName,
            albumName = albumName,
            sortDate = sortDate,
        )
    }

    private fun PlayTargetDto.toModel(): PlayTarget? = when (type) {
        "playlist" -> PlayTarget.Playlist(id.orEmpty(), name.orEmpty())
        "artist" -> name?.let { PlayTarget.Artist(it) }
        "album" -> name?.let { PlayTarget.Album(it, artist) }
        "song" -> path?.let { PlayTarget.Song(it, title ?: name ?: "Track") }
        "radio" -> {
            val seed = runCatching {
                PlayTarget.RadioSeedKind.valueOf(seedKind ?: "Artist")
            }.getOrDefault(PlayTarget.RadioSeedKind.Artist)
            PlayTarget.Radio(displayTitle ?: name.orEmpty(), seed, name.orEmpty(), path)
        }
        else -> null
    }
}
