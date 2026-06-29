package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeArtworkCache
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
    val year: Int? = null,
    val avgStars: Double? = null,
)

object LibraryLoader {
    private const val BROWSE_PLAYLIST_LIMIT = 500
    /** Page size for Library Artists/Albums infinite scroll. */
    const val BROWSE_PAGE_SIZE = 60

    private fun albumSubtitle(album: com.bockmedia.console.data.api.dto.AlbumItem): String {
        val parts = mutableListOf<String>()
        album.year?.takeIf { it > 0 }?.let { parts.add(it.toString()) }
        album.artist?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        album.avgStars?.takeIf { it > 0 }?.let { stars ->
            parts.add("★ ${"%.1f".format(stars)}")
        }
        if (parts.isEmpty() && album.tracks > 0) parts.add("${album.tracks} tracks")
        return parts.joinToString(" · ")
    }

    suspend fun loadArtistPage(
        repository: BockMediaRepository,
        page: Int,
        search: String = "",
    ): Pair<List<LibraryItem>, Int> {
        val resp = runCatching {
            repository.artists(page = page, search = search, limit = BROWSE_PAGE_SIZE)
        }.getOrNull() ?: return emptyList<LibraryItem>() to 0
        return artistItems(repository, resp.items) to resp.total
    }

    suspend fun loadAlbumPage(
        repository: BockMediaRepository,
        page: Int,
        search: String = "",
    ): Pair<List<LibraryItem>, Int> {
        val resp = runCatching {
            repository.albums(page = page, search = search, limit = BROWSE_PAGE_SIZE)
        }.getOrNull() ?: return emptyList<LibraryItem>() to 0
        return albumItems(repository, resp.items) to resp.total
    }

    private suspend fun artistItems(
        repository: BockMediaRepository,
        artists: List<com.bockmedia.console.data.api.dto.ArtistItem>,
    ): List<LibraryItem> = artists.map { artist ->
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

    private suspend fun albumItems(
        repository: BockMediaRepository,
        albums: List<com.bockmedia.console.data.api.dto.AlbumItem>,
    ): List<LibraryItem> = albums.map { album ->
        album.artPath?.let { repository.cacheArtPath(album.name, album.artist, it) }
        LibraryItem(
            id = "al-${album.name}\u0000${album.artist.orEmpty()}",
            title = album.name,
            subtitle = albumSubtitle(album),
            kind = LibraryItemKind.Album,
            playTarget = PlayTarget.Album(album.name, album.artist),
            albumName = album.name,
            artistName = album.artist,
            artPath = album.artPath,
            unplayed = album.unplayed,
            year = album.year,
            avgStars = album.avgStars,
        )
    }

    /** Playlists + offline; artists/albums paginate on demand in LibraryScreen. */
    suspend fun loadBuckets(
        repository: BockMediaRepository,
        context: Context,
    ): LibraryData = coroutineScope {
        val playlistsDef = async {
            runCatching {
                repository.playlists(search = "", limit = BROWSE_PLAYLIST_LIMIT, memberScoped = true).items
            }.getOrDefault(emptyList()).also { items ->
                items.forEach { pl ->
                    pl.artPath?.takeIf { it.isNotBlank() }?.let { HomeArtworkCache.storePlaylistPath(pl.id, it) }
                }
            }.map { pl ->
                LibraryItem(
                    id = "pl-${pl.id}",
                    title = pl.name,
                    subtitle = "${pl.tracks ?: 0} songs",
                    kind = LibraryItemKind.Playlist,
                    artPath = pl.artPath,
                    playTarget = PlayTarget.Playlist(pl.id, pl.name),
                    playlistId = pl.id,
                    sortDate = 0L,
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
            artists = emptyList(),
            albums = emptyList(),
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
                    repository.playlists(search = q, limit = BROWSE_PLAYLIST_LIMIT, memberScoped = true).items
                }.getOrDefault(emptyList())
                runCatching { repository.prefetchPlaylistCoverPaths(items.map { it.id }) }
                items.map { pl ->
                    LibraryItem(
                        id = "pl-${pl.id}",
                        title = pl.name,
                        subtitle = "${pl.tracks ?: 0} songs",
                        kind = LibraryItemKind.Playlist,
                        artPath = pl.artPath,
                        playTarget = PlayTarget.Playlist(pl.id, pl.name),
                        playlistId = pl.id,
                    )
                }
            }
            LibraryFilter.Artists -> loadArtistPage(repository, page = 1, search = q).first
            LibraryFilter.Albums -> loadAlbumPage(repository, page = 1, search = q).first
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
