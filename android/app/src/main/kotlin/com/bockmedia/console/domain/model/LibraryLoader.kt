package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.toPlayTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class LibraryFilter(val label: String) {
    All("All"),
    Playlists("Playlists"),
    Artists("Artists"),
    Albums("Albums"),
    Downloaded("Downloaded"),
}

enum class LibraryViewMode { List, Grid }

enum class LibrarySort { Recents, Name }

enum class LibraryItemKind { Playlist, Artist, Album, Downloaded }

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: LibraryItemKind,
    val playTarget: PlayTarget,
    val artPath: String? = null,
    val playlistId: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val sortDate: Long = 0L,
)

object LibraryLoader {
    suspend fun load(
        repository: BockMediaRepository,
        context: Context,
        filter: LibraryFilter,
        search: String = "",
    ): List<LibraryItem> = coroutineScope {
        val q = search.trim()
        val browsing = q.isEmpty()
        val listLimit = if (browsing) 50 else 200
        val needPlaylists = filter == LibraryFilter.All || filter == LibraryFilter.Playlists
        val needArtists = filter == LibraryFilter.All || filter == LibraryFilter.Artists
        val needAlbums = filter == LibraryFilter.All || filter == LibraryFilter.Albums
        val needOffline = filter == LibraryFilter.All || filter == LibraryFilter.Downloaded

        val playlistsDef = async {
            if (!needPlaylists) return@async emptyList()
            val items = runCatching {
                repository.playlists(search = q, limit = if (browsing) 200 else 100).items
            }.getOrDefault(emptyList())
            // One batch cover lookup so per-tile artwork resolves from cache instead of N calls.
            runCatching { repository.prefetchPlaylistCoverPaths(items.map { it.id }) }
            items
        }
        val artistsDef = async {
            if (!needArtists) return@async emptyList()
            runCatching {
                repository.artists(page = 1, search = q, limit = listLimit).items
            }.getOrDefault(emptyList())
        }
        val albumsDef = async {
            if (!needAlbums) return@async emptyList()
            runCatching {
                repository.albums(page = 1, search = q, limit = listLimit).items
            }.getOrDefault(emptyList())
        }
        val offlineDef = async {
            if (!needOffline) return@async emptyList<LibraryItem>()
            OfflineDownloadManager.refresh(context)
            val store = OfflineDownloadStore(context)
            store.listManifests()
                .filter { manifest -> store.isCollectionComplete(manifest) }
                .filter { manifest ->
                    if (q.isEmpty()) true
                    else manifest.title.contains(q, ignoreCase = true)
                }
                .map { manifest ->
                    LibraryItem(
                        id = "dl-${manifest.id}",
                        title = manifest.title,
                        subtitle = "${manifest.tracks.size} tracks · Offline",
                        kind = LibraryItemKind.Downloaded,
                        playTarget = manifest.toPlayTarget(),
                        artPath = manifest.coverArtPath,
                        playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId,
                        sortDate = manifest.lastSyncedAtMs.takeIf { it > 0 } ?: manifest.downloadedAtMs,
                    )
                }
        }

        val playlists = playlistsDef.await().map { pl ->
            LibraryItem(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = "${pl.tracks ?: 0} songs",
                kind = LibraryItemKind.Playlist,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                playlistId = pl.id,
                sortDate = 0L,
            )
        }
        val artists = artistsDef.await().map { artist ->
            LibraryItem(
                id = "ar-${artist.name.hashCode()}",
                title = artist.name,
                subtitle = "${artist.albums} albums · ${artist.tracks} songs",
                kind = LibraryItemKind.Artist,
                playTarget = PlayTarget.Artist(artist.name),
                artistName = artist.name,
            )
        }
        val albums = albumsDef.await().map { album ->
            LibraryItem(
                id = "al-${album.name.hashCode()}-${album.artist.orEmpty().hashCode()}",
                title = album.name,
                subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
                kind = LibraryItemKind.Album,
                playTarget = PlayTarget.Album(album.name, album.artist),
                albumName = album.name,
                artistName = album.artist,
            )
        }
        val offline = offlineDef.await()

        when (filter) {
            LibraryFilter.All -> playlists + artists + albums + offline
            LibraryFilter.Playlists -> playlists
            LibraryFilter.Artists -> artists
            LibraryFilter.Albums -> albums
            LibraryFilter.Downloaded -> offline
        }
    }
}
