package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.api.dto.AlbumItem
import com.bockmedia.console.data.api.dto.SearchHit
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

data class SearchBrowseFeed(
    val pickedForYou: List<HomeCard> = emptyList(),
    val newReleases: List<AlbumItem> = emptyList(),
    val genres: List<GenreItem> = emptyList(),
)

enum class SearchSuggestionKind { Artist, Album, Playlist, Song }

data class SearchSuggestion(
    val kind: SearchSuggestionKind,
    val title: String,
    val subtitle: String? = null,
    val id: String? = null,
    val path: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

object SearchBrowseLoader {
    private const val HISTORY_LIMIT = 40
    private const val PLAYLIST_LIMIT = 80
    private const val REQUEST_TIMEOUT_MS = 6_000L
    private const val ANALYTICS_TIMEOUT_MS = 3_000L

    /** Lightweight feed for first paint (genres + new releases). */
    suspend fun loadFast(repository: BockMediaRepository): SearchBrowseFeed = coroutineScope {
        val genresDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.genres(limit = 16) }.getOrNull()
            }
        }
        val newReleasesDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.recentAlbums(limit = 12) }.getOrNull()
            }
        }
        SearchBrowseFeed(
            genres = genresDef.await()?.items.orEmpty(),
            newReleases = newReleasesDef.await()?.items.orEmpty(),
        )
    }

    suspend fun load(repository: BockMediaRepository): SearchBrowseFeed = coroutineScope {
        val historyDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.streamHistory(1, HISTORY_LIMIT) }.getOrNull()
            }
        }
        val analyticsDef = async {
            withTimeoutOrNull(ANALYTICS_TIMEOUT_MS) {
                runCatching { repository.analytics() }.getOrNull()
            }
        }
        val playlistsDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.playlists(limit = PLAYLIST_LIMIT) }.getOrNull()
            }
        }
        val smartDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.smartPlaylists() }.getOrNull()
            }
        }
        val genresDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.genres(limit = 16) }.getOrNull()
            }
        }
        val newReleasesDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.recentAlbums(limit = 12) }.getOrNull()
            }
        }
        val dashboardDef = async {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching { repository.dashboardQuick() }.getOrNull()
            }
        }

        val history = historyDef.await()?.items.orEmpty()
        val analytics = analyticsDef.await()
        val allPlaylists = playlistsDef.await()?.items.orEmpty()
        val smartPlaylists = smartDef.await()?.items.orEmpty()
        val genres = genresDef.await()?.items.orEmpty()
        val newReleases = newReleasesDef.await()?.items.orEmpty()
        val dashboard = dashboardDef.await()

        val playlistByName = allPlaylists.associateBy { it.name.lowercase() }
        val playlistById = allPlaylists.associateBy { it.id }
        val usedArtPaths = mutableSetOf<String>()

        fun claimArt(path: String?): String? {
            if (path.isNullOrBlank() || path in usedArtPaths) return null
            usedArtPaths.add(path)
            return path
        }

        fun playlistCard(pl: com.bockmedia.console.data.api.dto.PlaylistSummary, artPath: String?, subtitle: String) =
            HomeCard(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = subtitle,
                artPath = artPath,
                playlistId = pl.id,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                kind = HomeSectionKind.Discover,
            )

        val recentPlaylistNames = linkedSetOf<String>()
        val artByPlaylist = mutableMapOf<String, String>()
        for (row in history) {
            row.playlist?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                recentPlaylistNames.add(name)
                row.filepath?.let { artByPlaylist.putIfAbsent(name.lowercase(), it) }
            }
        }

        val topGenres = analytics?.topGenres.orEmpty().take(6)
        val topArtists = analytics?.topArtists.orEmpty()

        val genreMixes = topGenres.mapIndexedNotNull { index, row ->
            val genre = row.name ?: row.label ?: return@mapIndexedNotNull null
            val smart = smartPlaylists.firstOrNull { sp ->
                !HomeFeedRules.isDailyMixName(sp.name) &&
                    (sp.name.contains(genre, ignoreCase = true) ||
                        sp.rules?.toString()?.contains(genre, ignoreCase = true) == true)
            }
            val linkedId = smart?.playlistId
            val artPath = claimArt(HomeFeedRules.artPathForGenreDistinct(history, genre, usedArtPaths))
                ?: claimArt(HomeFeedRules.artPathForArtistDistinct(
                    history,
                    HomeFeedRules.topArtistForGenre(history, genre) ?: topArtists.getOrNull(index)?.name.orEmpty(),
                    usedArtPaths,
                ))
                ?: claimArt(HomeFeedRules.nextDistinctArtPath(history, usedArtPaths))
            if (linkedId != null && playlistById[linkedId] != null) {
                playlistCard(playlistById[linkedId]!!, artPath, "$genre mix")
            } else {
                val named = allPlaylists.firstOrNull { HomeFeedRules.isGenreMixPlaylistName(it.name, genre) }
                if (named != null) {
                    playlistCard(named, artPath, "$genre mix")
                } else {
                    val seedArtist = HomeFeedRules.topArtistForGenre(history, genre)
                        ?: topArtists.getOrNull(index)?.name
                        ?: return@mapIndexedNotNull null
                    HomeCard(
                        id = "mix-$genre",
                        title = "$genre Mix",
                        subtitle = "Based on your listening",
                        artPath = artPath,
                        playTarget = PlayTarget.Artist(seedArtist),
                        kind = HomeSectionKind.TopMixes,
                    )
                }
            }
        }

        val recentAlbumCards = buildList {
            val seen = mutableSetOf<String>()
            for (row in history) {
                val album = row.album?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val key = "${album.lowercase()}|${row.artist?.lowercase().orEmpty()}"
                if (!seen.add(key)) continue
                add(
                    HomeCard(
                        id = "album-$key",
                        title = album,
                        subtitle = row.artist ?: "Recently played",
                        artPath = claimArt(row.filepath),
                        playTarget = PlayTarget.Album(album, row.artist),
                        kind = HomeSectionKind.JumpBackIn,
                    ),
                )
                if (size >= 4) break
            }
        }

        val discoverCards = allPlaylists
            .filter { HomeFeedRules.isDiscoverName(it.name) }
            .sortedByDescending { parseSortDate(it.createDate) }
            .take(4)
            .map { pl ->
                playlistCard(
                    pl,
                    claimArt(artByPlaylist[pl.name.lowercase()]),
                    "Discover",
                )
            }

        val recentPlaylistCards = recentPlaylistNames.mapNotNull { name ->
            val pl = playlistByName[name.lowercase()] ?: return@mapNotNull null
            playlistCard(pl, claimArt(artByPlaylist[name.lowercase()]), "Played recently")
        }.take(4)

        val dashboardCards = dashboardRecentCards(dashboard, playlistByName, artByPlaylist, ::claimArt)
        val pickedForYou = (dashboardCards + genreMixes + recentAlbumCards + discoverCards + recentPlaylistCards)
            .distinctBy { it.id }
            .take(12)

        SearchBrowseFeed(
            pickedForYou = pickedForYou,
            newReleases = newReleases,
            genres = genres,
        )
    }

    suspend fun suggest(repository: BockMediaRepository, query: String): List<SearchSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (q.length < 2) return suggestOneChar(repository, q)
        val response = runCatching { repository.search(q, limit = 8) }.getOrNull() ?: return emptyList()
        return suggestionsFromResponse(response)
    }

    suspend fun suggestOneChar(repository: BockMediaRepository, query: String): List<SearchSuggestion> =
        runCatching {
            repository.artists(1, query, limit = 6).items.map { artist ->
                SearchSuggestion(
                    kind = SearchSuggestionKind.Artist,
                    title = artist.name,
                    subtitle = "${artist.tracks} songs",
                )
            }
        }.getOrDefault(emptyList())

    fun suggestionsFromResponse(response: com.bockmedia.console.data.api.dto.SearchResponse): List<SearchSuggestion> =
        buildList {
            response.artists.take(2).forEach { hit ->
                add(
                    SearchSuggestion(
                        kind = SearchSuggestionKind.Artist,
                        title = hit.name ?: "",
                        subtitle = "Artist",
                        path = hit.path,
                    ),
                )
            }
            response.albums.take(2).forEach { hit ->
                add(
                    SearchSuggestion(
                        kind = SearchSuggestionKind.Album,
                        title = hit.name ?: "",
                        subtitle = hit.artist,
                        artist = hit.artist,
                        path = hit.path,
                    ),
                )
            }
            response.playlists.take(2).forEach { hit ->
                add(
                    SearchSuggestion(
                        kind = SearchSuggestionKind.Playlist,
                        title = hit.name ?: "",
                        subtitle = "Playlist",
                        id = hit.id,
                    ),
                )
            }
            response.songs.take(2).forEach { hit ->
                add(
                    SearchSuggestion(
                        kind = SearchSuggestionKind.Song,
                        title = hit.title ?: hit.name ?: "",
                        subtitle = hit.artist,
                        path = hit.path,
                        artist = hit.artist,
                        album = hit.album,
                    ),
                )
            }
        }.take(8)

    fun suggestionFromHit(kind: SearchSuggestionKind, hit: SearchHit): SearchSuggestion = when (kind) {
        SearchSuggestionKind.Artist -> SearchSuggestion(kind, hit.name ?: "", subtitle = hit.artist, path = hit.path)
        SearchSuggestionKind.Album -> SearchSuggestion(
            kind,
            hit.name ?: hit.album ?: "",
            subtitle = hit.artist,
            artist = hit.artist,
            path = hit.path,
        )
        SearchSuggestionKind.Playlist -> SearchSuggestion(kind, hit.name ?: "", id = hit.id)
        SearchSuggestionKind.Song -> SearchSuggestion(
            kind,
            hit.title ?: hit.name ?: "",
            subtitle = hit.artist,
            path = hit.path,
            artist = hit.artist,
            album = hit.album,
        )
    }

    fun playTargetFor(suggestion: SearchSuggestion): PlayTarget? = when (suggestion.kind) {
        SearchSuggestionKind.Artist -> PlayTarget.Artist(suggestion.title)
        SearchSuggestionKind.Album -> PlayTarget.Album(suggestion.title, suggestion.artist)
        SearchSuggestionKind.Playlist -> suggestion.id?.let { PlayTarget.Playlist(it, suggestion.title) }
        SearchSuggestionKind.Song -> suggestion.path?.let { PlayTarget.Song(it, suggestion.title) }
    }

    private fun parseSortDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrElse {
            runCatching { java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
    }
}

private fun dashboardRecentCards(
    dashboard: DashboardQuickResponse?,
    playlistByName: Map<String, com.bockmedia.console.data.api.dto.PlaylistSummary>,
    artByPlaylist: Map<String, String>,
    claimArt: (String?) -> String?,
): List<HomeCard> = dashboard?.recent.orEmpty().mapNotNull { item ->
    val playlistName = item.playlist?.trim()?.takeIf { it.isNotBlank() }
    if (playlistName != null) {
        val pl = playlistByName[playlistName.lowercase()] ?: return@mapNotNull null
        return@mapNotNull HomeCard(
            id = "dash-pl-${pl.id}",
            title = pl.name,
            subtitle = "Recently played",
            artPath = claimArt(artByPlaylist[playlistName.lowercase()] ?: item.path),
            playlistId = pl.id,
            playTarget = PlayTarget.Playlist(pl.id, pl.name),
            kind = HomeSectionKind.Discover,
        )
    }
    val path = item.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val title = item.track?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    HomeCard(
        id = "dash-${path.hashCode()}",
        title = title,
        subtitle = item.artist ?: "Recently played",
        artPath = claimArt(path),
        playTarget = PlayTarget.Song(path, title),
        kind = HomeSectionKind.Discover,
    )
}

