package com.bockmedia.console.media

import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.OfflineDownloadStore

class LocalPlaybackQueueResolver(
    private val repository: BockMediaRepository,
    private val offlineStore: OfflineDownloadStore,
) {
    suspend fun resolve(target: PlayTarget, shuffle: Boolean, maxTracks: Int? = null): List<LocalTrack> {
        val tracks = when (target) {
            is PlayTarget.Playlist -> loadPlaylistTracks(target.id, maxTracks)
            is PlayTarget.Artist -> loadArtistTracks(target.name, maxTracks)
            is PlayTarget.Album -> loadAlbumTracks(target.name, target.artist, maxTracks)
            is PlayTarget.Song -> listOf(songTrack(target.path, target.title, null, null))
            is PlayTarget.Radio -> when (target.seedKind) {
                PlayTarget.RadioSeedKind.Song -> {
                    target.path?.let { listOf(songTrack(it, target.name, null, null)) }
                        ?: loadArtistTracks(target.name, maxTracks)
                }
                PlayTarget.RadioSeedKind.Artist ->
                    loadArtistTracks(target.name, maxTracks)
                PlayTarget.RadioSeedKind.Genre ->
                    loadGenreRadioTracks(target, maxTracks)
            }
        }.filter { it.path.isNotBlank() }.distinctBy { it.path }

        if (tracks.isEmpty()) return emptyList()
        val capped = maxTracks?.let { tracks.take(it) } ?: tracks
        // ExoPlayer applies shuffle when enabled — do not pre-shuffle here.
        return capped
    }

    private suspend fun loadGenreRadioTracks(target: PlayTarget.Radio, maxTracks: Int?): List<LocalTrack> {
        val genre = target.genreLabel().orEmpty().ifBlank { return emptyList() }
        val cap = maxTracks ?: 150
        val libraryGenre = runCatching {
            repository.genres(limit = 200).items.let { items ->
                com.bockmedia.console.domain.model.HomeFeedRules.matchingLibraryGenreForLabel(genre, items)?.name
            }
        }.getOrNull() ?: genre
        val pool = loadGenreTracks(libraryGenre, minOf(cap, 200))
        if (pool.isEmpty()) return emptyList()
        val seed = pool.random()
        val mixed = runCatching {
            repository.resonanceRadio(seedKind = "song", path = seed.path, maxTracks = minOf(cap, 80))
        }.getOrNull()?.tracks?.mapNotNull { t ->
            val path = t.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            songTrack(path, t.title ?: path, t.artist, t.album, durationSec = t.duration)
        }?.filter { it.path.isNotBlank() }
        if (!mixed.isNullOrEmpty()) return mixed
        return pool.take(cap)
    }

    private suspend fun loadPlaylistTracks(id: String, maxTracks: Int?): List<LocalTrack> {
        val collectionId = if (id.startsWith("pl-")) id else "pl-$id"
        offlineStore.readManifest(collectionId)?.let { manifest ->
            val local = offlineStore.localTracksForManifest(manifest)
            if (local.isNotEmpty()) {
                return maxTracks?.let { local.take(it) } ?: local
            }
        }
        val out = mutableListOf<LocalTrack>()
        var page = 1
        while (true) {
            val resp = repository.playlistDetail(id, page = page, limit = 200)
            resp.tracks.forEach { t ->
                val path = t.path ?: return@forEach
                out += songTrack(path, t.title ?: path, t.artist, t.album, collectionId, t.duration)
                if (maxTracks != null && out.size >= maxTracks) return out
            }
            if (out.size >= resp.total || resp.tracks.isEmpty()) break
            if (maxTracks != null && out.size >= maxTracks) break
            page++
        }
        return out
    }

    private suspend fun loadArtistTracks(artist: String, maxTracks: Int?): List<LocalTrack> {
        val out = mutableListOf<LocalTrack>()
        var page = 1
        while (true) {
            val resp = repository.songs(page = page, search = artist, artist = artist)
            resp.items.forEach { t ->
                val path = t.path ?: return@forEach
                out += songTrack(path, t.title ?: path, t.artist, t.album, durationSec = t.duration)
                if (maxTracks != null && out.size >= maxTracks) return out
            }
            if (resp.items.isEmpty() || out.size >= resp.total) break
            if (maxTracks != null && out.size >= maxTracks) break
            page++
        }
        return out
    }

    private suspend fun loadGenreTracks(genre: String, maxTracks: Int?): List<LocalTrack> {
        val out = mutableListOf<LocalTrack>()
        var page = 1
        while (true) {
            val resp = repository.songs(page = page, genre = genre, limit = 200)
            resp.items.forEach { t ->
                val path = t.path ?: return@forEach
                out += songTrack(path, t.title ?: path, t.artist, t.album, durationSec = t.duration)
                if (maxTracks != null && out.size >= maxTracks) return out
            }
            if (resp.items.isEmpty() || out.size >= resp.total) break
            if (maxTracks != null && out.size >= maxTracks) break
            page++
        }
        return out
    }

    private suspend fun loadAlbumTracks(album: String, artist: String?, maxTracks: Int?): List<LocalTrack> {
        val out = mutableListOf<LocalTrack>()
        var page = 1
        while (true) {
            val resp = repository.songs(page = page, search = album, artist = artist, album = album)
            resp.items.forEach { t ->
                val path = t.path ?: return@forEach
                out += songTrack(path, t.title ?: path, t.artist, t.album, durationSec = t.duration)
                if (maxTracks != null && out.size >= maxTracks) return out
            }
            if (resp.items.isEmpty() || out.size >= resp.total) break
            if (maxTracks != null && out.size >= maxTracks) break
            page++
        }
        return out
    }

    private suspend fun songTrack(
        path: String,
        title: String,
        artist: String?,
        album: String?,
        playlistId: String? = null,
        durationSec: Int? = null,
    ): LocalTrack {
        val local = offlineStore.localFileFor(path, playlistId)
        val durationMs = durationSec?.takeIf { it > 0 }?.times(1000L) ?: 0L
        return LocalTrack(
            path = path,
            title = title,
            artist = artist,
            album = album,
            localFile = local,
            durationMs = durationMs,
        )
    }
}
