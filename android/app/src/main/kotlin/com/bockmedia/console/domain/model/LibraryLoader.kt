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
    Downloaded("Downloads"),
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
    val unplayed: Boolean = false,
)

object LibraryLoader {
    private const val BROWSE_PLAYLIST_LIMIT = 500
    private const val BROWSE_ARTIST_LIMIT = 100
    private const val BROWSE_ALBUM_LIMIT = 100
    private const val SEARCH_LIMIT = 100

    /** Load all library buckets in parallel — filter client-side for instant tab switches. */
    suspend fun loadBuckets(
        repository: BockMediaRepository,
        context: Context,
    ): LibraryData = coroutineScope {
        val playlistsDef = async {
            runCatching {
                repository.playlists(search = "", limit = BROWSE_PLAYLIST_LIMIT).items
            }.getOrDefault(emptyList()).map { pl ->
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
        }
        val artistsDef = async {
            runCatching {
                repository.artists(page = 1, search = "", limit = BROWSE_ARTIST_LIMIT).items
            }.getOrDefault(emptyList()).map { artist ->
                artist.artPath?.let { repository.cacheArtistArtPath(artist.name, it) }
                LibraryItem(
                    id = "ar-${artist.name}",
                    title = artist.name,
                    subtitle = "${artist.albums} albums · ${artist.tracks} songs",
                    kind = LibraryItemKind.Artist,
                    playTarget = PlayTarget.Artist(artist.name),
                    artistName = artist.name,
                    artPath = artist.artPath,
                )
            }
        }
        val albumsDef = async {
            runCatching {
                repository.albums(page = 1, search = "", limit = BROWSE_ALBUM_LIMIT).items
            }.getOrDefault(emptyList()).map { album ->
                album.artPath?.let { repository.cacheArtPath(album.name, album.artist, it) }
                LibraryItem(
                    id = "al-${album.name}\u0000${album.artist.orEmpty()}",
                    title = album.name,
                    subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
                    kind = LibraryItemKind.Album,
                    playTarget = PlayTarget.Album(album.name, album.artist),
                    albumName = album.name,
                    artistName = album.artist,
                    artPath = album.artPath,
                    unplayed = album.unplayed,
                )
            }
        }
        val offlineDef = async {
            OfflineDownloadManager.refresh(context)
            val store = OfflineDownloadStore(context)
            store.listManifests()
                .filter { manifest -> store.isCollectionComplete(manifest) }
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

        LibraryData(
            playlists = playlistsDef.await(),
            artists = artistsDef.await(),
            albums = albumsDef.await(),
            offline = offlineDef.await(),
        )
    }

    /** Search hits one category at a time (server-side filter). */
    suspend fun search(
        repository: BockMediaRepository,
        context: Context,
        filter: LibraryFilter,
        query: String,
    ): List<LibraryItem> = coroutineScope {
        val q = query.trim()
        if (q.isEmpty()) return@coroutineScope emptyList()
        when (filter) {
            LibraryFilter.Playlists, LibraryFilter.All -> {
                val items = runCatching {
                    repository.playlists(search = q, limit = SEARCH_LIMIT).items
                }.getOrDefault(emptyList())
                runCatching { repository.prefetchPlaylistCoverPaths(items.map { it.id }) }
                items.map { pl ->
                    LibraryItem(
                        id = "pl-${pl.id}",
                        title = pl.name,
                        subtitle = "${pl.tracks ?: 0} songs",
                        kind = LibraryItemKind.Playlist,
                        playTarget = PlayTarget.Playlist(pl.id, pl.name),
                        playlistId = pl.id,
                    )
                }
            }
            LibraryFilter.Artists -> {
                runCatching {
                    repository.artists(page = 1, search = q, limit = SEARCH_LIMIT).items
                }.getOrDefault(emptyList()).map { artist ->
                    LibraryItem(
                        id = "ar-${artist.name}",
                        title = artist.name,
                        subtitle = "${artist.albums} albums · ${artist.tracks} songs",
                        kind = LibraryItemKind.Artist,
                        playTarget = PlayTarget.Artist(artist.name),
                        artistName = artist.name,
                        artPath = artist.artPath,
                    )
                }
            }
            LibraryFilter.Albums -> {
                runCatching {
                    repository.albums(page = 1, search = q, limit = SEARCH_LIMIT).items
                }.getOrDefault(emptyList()).map { album ->
                    LibraryItem(
                        id = "al-${album.name}\u0000${album.artist.orEmpty()}",
                        title = album.name,
                        subtitle = listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "),
                        kind = LibraryItemKind.Album,
                        playTarget = PlayTarget.Album(album.name, album.artist),
                        albumName = album.name,
                        artistName = album.artist,
                        artPath = album.artPath,
                        unplayed = album.unplayed,
                    )
                }
            }
            LibraryFilter.Downloaded -> {
                OfflineDownloadManager.refresh(context)
                OfflineDownloadStore(context).listManifests()
                    .filter { it.title.contains(q, ignoreCase = true) }
                    .map { manifest ->
                        LibraryItem(
                            id = "dl-${manifest.id}",
                            title = manifest.title,
                            subtitle = "${manifest.tracks.size} tracks · Offline",
                            kind = LibraryItemKind.Downloaded,
                            playTarget = manifest.toPlayTarget(),
                            artPath = manifest.coverArtPath,
                            playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId,
                        )
                    }
            }
        }
    }
}
