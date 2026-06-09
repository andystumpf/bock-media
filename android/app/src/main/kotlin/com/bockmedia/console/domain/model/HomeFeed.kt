package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.RecentItem
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.toPlayTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

enum class HomeFilter(val label: String) {
    All("All"),
    Playlists("Playlists"),
    Mixes("Mixes"),
    Radio("Radio"),
    Discover("Discover"),
    Offline("Offline"),
}

enum class HomeSectionKind {
    JumpBackIn,
    Favorites,
    TopMixes,
    DailyMixes,
    RecentPlaylists,
    Radio,
    Discover,
    Offline,
}

data class HomeCard(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artPath: String? = null,
    val playlistId: String? = null,
    val playTarget: PlayTarget,
    val kind: HomeSectionKind,
)

data class HomeSection(
    val id: String,
    val title: String,
    val kind: HomeSectionKind,
    val cards: List<HomeCard>,
)

data class HomeFeed(
    val sections: List<HomeSection>,
)

object HomeFeedLoader {
    private const val HISTORY_LIMIT = 100
    private const val PLAYLIST_LIMIT = 300
    private const val ANALYTICS_TIMEOUT_MS = 4_000L

    suspend fun load(repository: BockMediaRepository): HomeFeed = coroutineScope {
        val historyDef = async { runCatching { repository.streamHistory(1, HISTORY_LIMIT) }.getOrNull() }
        val analyticsDef = async {
            withTimeoutOrNull(ANALYTICS_TIMEOUT_MS) {
                runCatching { repository.analytics() }.getOrNull()
            }
        }
        val playlistsDef = async { runCatching { repository.playlists(limit = PLAYLIST_LIMIT) }.getOrNull() }
        val smartDef = async { runCatching { repository.smartPlaylists() }.getOrNull() }
        val favoritesDef = async { runCatching { repository.favorites() }.getOrNull().orEmpty() }
        val dashboardDef = async { runCatching { repository.dashboardQuick() }.getOrNull() }

        val history = historyDef.await()?.items.orEmpty()
        val analytics = analyticsDef.await()
        val allPlaylists = playlistsDef.await()?.items.orEmpty()
        val smartPlaylists = smartDef.await()?.items.orEmpty()
        val dashboard = dashboardDef.await()
        val favorites = dashboard?.favorites?.takeIf { it.isNotEmpty() } ?: favoritesDef.await()

        val playlistByName = allPlaylists.associateBy { it.name.lowercase() }
        val playlistById = allPlaylists.associateBy { it.id }

        fun playlistCard(
            pl: PlaylistSummary,
            artPath: String? = null,
            kind: HomeSectionKind,
            subtitle: String? = "${pl.tracks} tracks",
        ) = HomeCard(
            id = "pl-${pl.id}",
            title = pl.name,
            subtitle = subtitle,
            artPath = artPath,
            playlistId = pl.id,
            playTarget = PlayTarget.Playlist(pl.id, pl.name),
            kind = kind,
        )

        fun cardFromName(
            name: String,
            artPath: String? = null,
            kind: HomeSectionKind,
            subtitle: String? = null,
        ): HomeCard? {
            val pl = playlistByName[name.lowercase()] ?: return null
            return playlistCard(pl, artPath, kind, subtitle)
        }

        val recentPlaylistNames = linkedSetOf<String>()
        val artByPlaylist = mutableMapOf<String, String>()
        for (row in history) {
            row.playlist?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                recentPlaylistNames.add(name)
                row.filepath?.let { artByPlaylist.putIfAbsent(name.lowercase(), it) }
            }
        }

        val recentlyPlayedPlaylists = recentPlaylistNames.mapNotNull { name ->
            cardFromName(name, artByPlaylist[name.lowercase()], HomeSectionKind.JumpBackIn, "Recently played")
        }.take(6)

        val recentAlbums = buildList {
            val seen = mutableSetOf<String>()
            for (row in history) {
                val album = row.album?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val key = "${album.lowercase()}|${row.artist?.lowercase().orEmpty()}"
                if (!seen.add(key)) continue
                add(
                    HomeCard(
                        id = "album-$key",
                        title = album,
                        subtitle = row.artist ?: "Recently played album",
                        artPath = row.filepath,
                        playTarget = PlayTarget.Album(album, row.artist),
                        kind = HomeSectionKind.JumpBackIn,
                    ),
                )
                if (size >= 4) break
            }
        }

        val recentlyAdded = allPlaylists
            .sortedByDescending { parseSortDate(it.createDate) }
            .filter { pl ->
                recentlyPlayedPlaylists.none { it.title.equals(pl.name, ignoreCase = true) }
            }
            .take(6)
            .map { playlistCard(it, kind = HomeSectionKind.JumpBackIn, subtitle = "Recently added") }

        val jumpBackIn = (dashboardJumpCards(dashboard, playlistByName, artByPlaylist) +
            recentlyPlayedPlaylists + recentAlbums + recentlyAdded)
            .distinctBy { it.id }
            .take(16)

        val favoriteCards = favorites.take(12).map { fav ->
            HomeCard(
                id = "fav-${fav.path.hashCode()}",
                title = fav.track ?: "Favorite",
                subtitle = fav.artist ?: "Liked song",
                artPath = fav.path,
                playTarget = PlayTarget.Song(fav.path, fav.track ?: "Favorite"),
                kind = HomeSectionKind.Favorites,
            )
        }

        val topGenres = analytics?.topGenres.orEmpty().take(6)
        val topArtists = analytics?.topArtists.orEmpty()
        val usedArtPaths = mutableSetOf<String>()

        fun claimArtPath(path: String?): String? {
            if (path.isNullOrBlank() || path in usedArtPaths) return null
            usedArtPaths.add(path)
            return path
        }

        fun resolveMixArt(genre: String, artist: String?, index: Int): String? {
            claimArtPath(HomeFeedRules.artPathForGenreDistinct(history, genre, usedArtPaths))?.let { return it }
            artist?.let { a ->
                claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, usedArtPaths))?.let { return it }
            }
            topArtists.getOrNull(index)?.name?.let { a ->
                claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, usedArtPaths))?.let { return it }
            }
            return claimArtPath(HomeFeedRules.nextDistinctArtPath(history, usedArtPaths))
        }

        val genreMixes = topGenres.mapIndexedNotNull { index, row ->
            val genre = row.name ?: row.label ?: return@mapIndexedNotNull null
            val smart = smartPlaylists.firstOrNull { sp ->
                !HomeFeedRules.isDailyMixName(sp.name) &&
                    (sp.name.contains(genre, ignoreCase = true) ||
                        sp.rules?.toString()?.contains(genre, ignoreCase = true) == true)
            }
            val linkedId = smart?.playlistId
            if (linkedId != null && playlistById[linkedId] != null) {
                val pl = playlistById[linkedId]!!
                if (HomeFeedRules.isDailyMixName(pl.name)) return@mapIndexedNotNull null
                return@mapIndexedNotNull playlistCard(
                    pl,
                    resolveMixArt(genre, HomeFeedRules.topArtistForGenre(history, genre), index),
                    HomeSectionKind.TopMixes,
                    "$genre mix",
                )
            }
            val named = allPlaylists.firstOrNull { pl ->
                HomeFeedRules.isGenreMixPlaylistName(pl.name, genre)
            } ?: allPlaylists.firstOrNull { it.name.equals("$genre Mix", ignoreCase = true) }
            if (named != null) {
                playlistCard(
                    named,
                    resolveMixArt(genre, HomeFeedRules.topArtistForGenre(history, genre), index),
                    HomeSectionKind.TopMixes,
                    "$genre mix",
                )
            } else {
                val seedArtist = HomeFeedRules.topArtistForGenre(history, genre)
                    ?: topArtists.getOrNull(index)?.name
                    ?: topArtists.firstOrNull()?.name
                    ?: return@mapIndexedNotNull null
                HomeCard(
                    id = "mix-$genre",
                    title = "$genre Mix",
                    subtitle = "Based on your listening",
                    artPath = resolveMixArt(genre, seedArtist, index),
                    playTarget = PlayTarget.Artist(seedArtist),
                    kind = HomeSectionKind.TopMixes,
                )
            }
        }

        val dailyMixes = buildList {
            smartPlaylists.filter { it.enabled && HomeFeedRules.isDailyMixName(it.name) }.forEach { sp ->
                val pl = sp.playlistId?.let { playlistById[it] } ?: return@forEach
                add(
                    playlistCard(
                        pl,
                        artPathForPlaylistSeed(history, pl.name),
                        HomeSectionKind.DailyMixes,
                        "Daily mix",
                    ),
                )
            }
            allPlaylists
                .filter { HomeFeedRules.isDailyMixName(it.name) }
                .sortedByDescending { parseSortDate(it.lastUsed) }
                .forEach { pl ->
                    add(
                        playlistCard(
                            pl,
                            artPathForPlaylistSeed(history, pl.name),
                            HomeSectionKind.DailyMixes,
                            "Daily mix",
                        ),
                    )
                }
        }.distinctBy { it.id }.take(8)

        val recentPlaylists = recentPlaylistNames.mapNotNull { name ->
            cardFromName(name, artByPlaylist[name.lowercase()], HomeSectionKind.RecentPlaylists, "Played recently")
        }.take(12)

        val radioCards = buildRadioCards(history, topArtists, topGenres, allPlaylists, usedArtPaths)

        val playedPlaylistIds = jumpBackIn.mapNotNull { it.playlistId }.toSet()
        val playedPlaylistNames = recentPlaylistNames.map { it.lowercase() }.toSet()

        val discoverCandidates = buildList {
            allPlaylists
                .filter { HomeFeedRules.isDiscoverName(it.name) && it.id !in playedPlaylistIds }
                .sortedByDescending { parseSortDate(it.createDate) }
                .forEach { add(playlistCard(it, kind = HomeSectionKind.Discover, subtitle = "Discover Weekly")) }

            smartPlaylists.filter { it.enabled && HomeFeedRules.isDiscoverName(it.name) }.forEach { sp ->
                val pl = sp.playlistId?.let { playlistById[it] } ?: return@forEach
                if (pl.id !in playedPlaylistIds) {
                    add(playlistCard(pl, kind = HomeSectionKind.Discover, subtitle = "New to you"))
                }
            }

            smartPlaylists.filter { it.enabled && !HomeFeedRules.isDailyMixName(it.name) }.forEach { sp ->
                val pl = sp.playlistId?.let { playlistById[it] } ?: return@forEach
                if (pl.id !in playedPlaylistIds && pl.name.lowercase() !in playedPlaylistNames) {
                    add(playlistCard(pl, kind = HomeSectionKind.Discover, subtitle = "Smart playlist"))
                }
            }

            allPlaylists
                .filter { pl ->
                    pl.id !in playedPlaylistIds &&
                        pl.name.lowercase() !in playedPlaylistNames &&
                        !HomeFeedRules.isDailyMixName(pl.name) &&
                        !HomeFeedRules.isGenreMixPlaylistName(pl.name) &&
                        !HomeFeedRules.isExplicitRadioPlaylistName(pl.name)
                }
                .sortedByDescending { it.tracks }
                .take(8)
                .forEach { add(playlistCard(it, kind = HomeSectionKind.Discover, subtitle = "${it.tracks} tracks")) }
        }.distinctBy { it.id }.take(12)

        val sections = listOfNotNull(
            section("jump-back-in", "Jump back in", HomeSectionKind.JumpBackIn, jumpBackIn),
            section("favorites", "Your favorites", HomeSectionKind.Favorites, favoriteCards),
            section("top-mixes", "Your top mixes", HomeSectionKind.TopMixes, genreMixes),
            section("daily-mixes", "Daily mixes", HomeSectionKind.DailyMixes, dailyMixes),
            section("recent-playlists", "Recent playlists", HomeSectionKind.RecentPlaylists, recentPlaylists),
            section("radio", "Radio", HomeSectionKind.Radio, radioCards),
            section("discover", "Discover", HomeSectionKind.Discover, discoverCandidates),
        )

        HomeFeed(sections)
    }

    private fun buildRadioCards(
        history: List<StreamHistoryItem>,
        topArtists: List<com.bockmedia.console.data.api.dto.CountRow>,
        topGenres: List<com.bockmedia.console.data.api.dto.CountRow>,
        allPlaylists: List<PlaylistSummary>,
        usedArtPaths: MutableSet<String>,
    ): List<HomeCard> {
        fun claimArtPath(path: String?): String? {
            if (path.isNullOrBlank() || path in usedArtPaths) return null
            usedArtPaths.add(path)
            return path
        }

        fun resolveRadioArt(
            preferred: String?,
            artist: String?,
            fallbackIndex: Int,
        ): String? {
            claimArtPath(preferred)?.let { return it }
            artist?.let { a ->
                claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, usedArtPaths))?.let { return it }
            }
            topArtists.getOrNull(fallbackIndex)?.name?.let { a ->
                claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, usedArtPaths))?.let { return it }
            }
            return claimArtPath(HomeFeedRules.nextDistinctArtPath(history, usedArtPaths))
        }

        return buildList {
        topArtists.take(6).forEachIndexed { index, row ->
            val artist = row.name ?: row.label ?: return@forEachIndexed
            add(
                HomeCard(
                    id = "radio-artist-$artist",
                    title = "$artist Radio",
                    subtitle = "Infinite · artist seed",
                    artPath = resolveRadioArt(null, artist, index),
                    playTarget = PlayTarget.Radio(
                        displayTitle = "$artist Radio",
                        seedKind = PlayTarget.RadioSeedKind.Artist,
                        name = artist,
                    ),
                    kind = HomeSectionKind.Radio,
                ),
            )
        }

        val songSeeds = history
            .filter { it.filepath != null && !it.track.isNullOrBlank() }
            .distinctBy { it.filepath }
            .take(3)
        songSeeds.forEachIndexed { index, row ->
            val title = row.track!!
            add(
                HomeCard(
                    id = "radio-song-${row.filepath}",
                    title = title,
                    subtitle = listOfNotNull(row.artist, "Song radio").joinToString(" · "),
                    artPath = resolveRadioArt(row.filepath, row.artist, index),
                    playTarget = PlayTarget.Radio(
                        displayTitle = "$title Radio",
                        seedKind = PlayTarget.RadioSeedKind.Song,
                        name = title,
                        path = row.filepath,
                    ),
                    kind = HomeSectionKind.Radio,
                ),
            )
        }

        topGenres.take(4).forEachIndexed { index, row ->
            val genre = row.name ?: row.label ?: return@forEachIndexed
            val seedArtist = HomeFeedRules.topArtistForGenre(history, genre)
                ?: topArtists.getOrNull(index)?.name
                ?: return@forEachIndexed
            add(
                HomeCard(
                    id = "radio-genre-$genre",
                    title = "$genre Radio",
                    subtitle = "Infinite · genre seed",
                    artPath = resolveRadioArt(
                        HomeFeedRules.artPathForGenreDistinct(history, genre, usedArtPaths),
                        seedArtist,
                        index,
                    ),
                    playTarget = PlayTarget.Radio(
                        displayTitle = "$genre Radio",
                        seedKind = PlayTarget.RadioSeedKind.Genre,
                        name = seedArtist,
                    ),
                    kind = HomeSectionKind.Radio,
                ),
            )
        }

        allPlaylists
            .filter { HomeFeedRules.isExplicitRadioPlaylistName(it.name) }
            .sortedByDescending { it.tracks }
            .take(4)
            .forEach { pl ->
                add(
                    HomeCard(
                        id = "pl-${pl.id}",
                        title = pl.name,
                        subtitle = "Radio station",
                        artPath = claimArtPath(artPathForPlaylistSeed(history, pl.name)),
                        playlistId = pl.id,
                        playTarget = PlayTarget.Playlist(pl.id, pl.name),
                        kind = HomeSectionKind.Radio,
                    ),
                )
            }
        }.distinctBy { it.id }.take(12)
    }

    private fun artPathForPlaylistSeed(history: List<StreamHistoryItem>, playlistName: String): String? =
        history.firstOrNull { row ->
            row.filepath != null && row.playlist.equals(playlistName, ignoreCase = true)
        }?.filepath

    private fun section(id: String, title: String, kind: HomeSectionKind, cards: List<HomeCard>): HomeSection? {
        if (cards.isEmpty()) return null
        return HomeSection(id, title, kind, cards)
    }

    private fun parseSortDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }
}

private fun dashboardJumpCards(
    dashboard: DashboardQuickResponse?,
    playlistByName: Map<String, PlaylistSummary>,
    artByPlaylist: Map<String, String>,
): List<HomeCard> {
    return dashboard?.recent.orEmpty().mapNotNull { item ->
        cardFromRecentItem(item, playlistByName, artByPlaylist)
    }
}

private fun cardFromRecentItem(
    item: RecentItem,
    playlistByName: Map<String, PlaylistSummary>,
    artByPlaylist: Map<String, String>,
): HomeCard? {
    val path = item.path?.takeIf { it.isNotBlank() }
    val playlistName = item.playlist?.trim()?.takeIf { it.isNotBlank() }
    if (playlistName != null) {
        val pl = playlistByName[playlistName.lowercase()] ?: return null
        return HomeCard(
            id = "dash-pl-${pl.id}",
            title = pl.name,
            subtitle = "Recently played",
            artPath = artByPlaylist[playlistName.lowercase()] ?: path,
            playlistId = pl.id,
            playTarget = PlayTarget.Playlist(pl.id, pl.name),
            kind = HomeSectionKind.JumpBackIn,
        )
    }
    if (path == null) return null
    val title = item.track?.takeIf { it.isNotBlank() } ?: return null
    return HomeCard(
        id = "dash-${path.hashCode()}",
        title = title,
        subtitle = item.artist ?: "Recently played",
        artPath = path,
        playTarget = PlayTarget.Song(path, title),
        kind = HomeSectionKind.JumpBackIn,
    )
}

fun HomeFilter.matches(kind: HomeSectionKind): Boolean = when (this) {
    HomeFilter.All -> true
    HomeFilter.Offline -> false
    HomeFilter.Playlists -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists || kind == HomeSectionKind.Favorites
    HomeFilter.Mixes -> kind == HomeSectionKind.TopMixes || kind == HomeSectionKind.DailyMixes
    HomeFilter.Radio -> kind == HomeSectionKind.Radio
    HomeFilter.Discover -> kind == HomeSectionKind.Discover
}

fun buildOfflineHomeSection(context: Context): HomeSection? {
    OfflineDownloadManager.refresh(context)
    val store = OfflineDownloadStore(context)
    val cards = store.listManifests()
        .filter { manifest -> store.isCollectionComplete(manifest) }
        .sortedByDescending { it.lastSyncedAtMs.takeIf { ms -> ms > 0 } ?: it.downloadedAtMs }
        .map { manifest ->
            HomeCard(
                id = "offline-${manifest.id}",
                title = manifest.title,
                subtitle = "${manifest.tracks.size} tracks · Available offline",
                artPath = manifest.coverArtPath,
                playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId,
                playTarget = manifest.toPlayTarget(),
                kind = HomeSectionKind.Offline,
            )
        }
    if (cards.isEmpty()) return null
    return HomeSection("offline-library", "Your downloads", HomeSectionKind.Offline, cards)
}
