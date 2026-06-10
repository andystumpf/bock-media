package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.SmartPlaylist
import com.bockmedia.console.data.api.dto.StreamHistoryItem
object HomeFeedLimits {
    const val JUMP_BACK_IN = 24
    const val FAVORITES = 16
    const val TOP_MIXES = 14
    const val DAILY_MIXES = 12
    const val RECENT_PLAYLISTS = 24
    const val RADIO = 16
    const val DISCOVER = 24
    const val MORE_PLAYLISTS = 24
}

data class HomeFeedInput(
    val history: List<StreamHistoryItem>,
    val analytics: AnalyticsResponse?,
    val allPlaylists: List<PlaylistSummary>,
    val smartPlaylists: List<SmartPlaylist>,
    val favorites: List<FavoriteItem>,
    val dashboard: DashboardQuickResponse?,
    val shuffleSeed: Long,
)

private class HomeFeedRegistry {
    val usedPlaylistIds = mutableSetOf<String>()
    val usedPlaylistNameKeys = mutableSetOf<String>()
    val usedCardIds = mutableSetOf<String>()
    val usedArtPaths = mutableSetOf<String>()

    fun claimPlaylist(id: String, name: String): Boolean {
        val nameKey = name.lowercase()
        if (id in usedPlaylistIds || nameKey in usedPlaylistNameKeys) return false
        usedPlaylistIds.add(id)
        usedPlaylistNameKeys.add(nameKey)
        return true
    }

    fun registerCard(card: HomeCard) {
        usedCardIds.add(card.id)
        card.playlistId?.let { usedPlaylistIds.add(it) }
        if (card.playlistId != null) usedPlaylistNameKeys.add(card.title.lowercase())
    }

    fun hasCard(id: String) = id in usedCardIds

    fun canUsePlaylist(playlist: PlaylistSummary) =
        playlist.id !in usedPlaylistIds && playlist.name.lowercase() !in usedPlaylistNameKeys

    fun claimArtPath(path: String?): String? {
        if (path.isNullOrBlank() || path in usedArtPaths) return null
        usedArtPaths.add(path)
        return path
    }
}

object HomeFeedComposer {
    fun compose(input: HomeFeedInput): HomeFeed {
        val registry = HomeFeedRegistry()
        val playlistByName = input.allPlaylists.associateBy { it.name.lowercase() }
        val playlistById = input.allPlaylists.associateBy { it.id }
        val topGenres = input.analytics?.topGenres.orEmpty().take(8)
        val topArtists = input.analytics?.topArtists.orEmpty()
        val shuffledGeneric = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, input.shuffleSeed)

        val recentPlaylistNames = linkedSetOf<String>()
        val artByPlaylist = mutableMapOf<String, String>()
        for (row in input.history) {
            row.playlist?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                recentPlaylistNames.add(name)
                row.filepath?.let { artByPlaylist.putIfAbsent(name.lowercase(), it) }
            }
        }

        fun playlistCard(
            pl: PlaylistSummary,
            artPath: String? = null,
            kind: HomeSectionKind,
            subtitle: String? = null,
            claim: Boolean = true,
        ): HomeCard? {
            if (claim && !registry.claimPlaylist(pl.id, pl.name)) return null
            val card = HomeCard(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = subtitle ?: "${pl.tracks} tracks",
                artPath = artPath,
                playlistId = pl.id,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                kind = kind,
            )
            registry.registerCard(card)
            return card
        }

        fun fillPlaylists(
            pool: List<PlaylistSummary>,
            target: Int,
            kind: HomeSectionKind,
            subtitle: (PlaylistSummary) -> String,
        ): List<HomeCard> {
            val cards = mutableListOf<HomeCard>()
            for (pl in pool) {
                if (cards.size >= target) break
                if (pl.tracks <= 0 || !registry.canUsePlaylist(pl)) continue
                playlistCard(pl, kind = kind, subtitle = subtitle(pl))?.let { cards.add(it) }
            }
            return cards
        }

        fun resolveMixArt(genre: String, artist: String?, index: Int): String? {
            registry.claimArtPath(HomeFeedRules.artPathForGenreDistinct(input.history, genre, registry.usedArtPaths))?.let { return it }
            artist?.let { a ->
                registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, a, registry.usedArtPaths))?.let { return it }
            }
            topArtists.getOrNull(index)?.name?.let { a ->
                registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, a, registry.usedArtPaths))?.let { return it }
            }
            return registry.claimArtPath(HomeFeedRules.nextDistinctArtPath(input.history, registry.usedArtPaths))
        }

        val jumpBackIn = buildList {
            for (card in dashboardJumpCards(input.dashboard, playlistByName, artByPlaylist)) {
                if (size >= HomeFeedLimits.JUMP_BACK_IN) break
                if (!registry.hasCard(card.id)) {
                    registry.registerCard(card)
                    add(card)
                }
            }
            for (name in recentPlaylistNames) {
                if (size >= HomeFeedLimits.JUMP_BACK_IN) break
                val pl = playlistByName[name.lowercase()] ?: continue
                playlistCard(pl, artByPlaylist[name.lowercase()], HomeSectionKind.JumpBackIn, "Recently played")?.let { add(it) }
            }
            val seenAlbums = mutableSetOf<String>()
            for (row in input.history) {
                if (size >= HomeFeedLimits.JUMP_BACK_IN) break
                val album = row.album?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val key = "${album.lowercase()}|${row.artist?.lowercase().orEmpty()}"
                if (!seenAlbums.add(key)) continue
                val card = HomeCard(
                    id = "album-$key",
                    title = album,
                    subtitle = row.artist ?: "Recently played album",
                    artPath = row.filepath,
                    playTarget = PlayTarget.Album(album, row.artist),
                    kind = HomeSectionKind.JumpBackIn,
                )
                if (!registry.hasCard(card.id)) {
                    registry.registerCard(card)
                    add(card)
                }
            }
            addAll(
                fillPlaylists(
                    input.allPlaylists.sortedByDescending { parseSortDate(it.createDate) },
                    HomeFeedLimits.JUMP_BACK_IN - size,
                    HomeSectionKind.JumpBackIn,
                ) { "Recently added" },
            )
            addAll(
                fillPlaylists(
                    shuffledGeneric,
                    HomeFeedLimits.JUMP_BACK_IN - size,
                    HomeSectionKind.JumpBackIn,
                ) { "${it.tracks} tracks · From your library" },
            )
        }.distinctBy { it.id }.take(HomeFeedLimits.JUMP_BACK_IN)

        val favoriteCards = buildList {
            for (fav in input.favorites) {
                if (size >= HomeFeedLimits.FAVORITES) break
                val card = HomeCard(
                    id = "fav-${fav.path}",
                    title = fav.track ?: "Favorite",
                    subtitle = fav.artist ?: "Liked song",
                    artPath = fav.path,
                    playTarget = PlayTarget.Song(fav.path, fav.track ?: "Favorite"),
                    kind = HomeSectionKind.Favorites,
                )
                if (!registry.hasCard(card.id)) {
                    registry.registerCard(card)
                    add(card)
                }
            }
        }

        val genreMixes = buildList {
            for ((index, row) in topGenres.withIndex()) {
                if (size >= HomeFeedLimits.TOP_MIXES) break
                val genre = row.name ?: row.label ?: continue
                val smart = input.smartPlaylists.firstOrNull { sp ->
                    !HomeFeedRules.isDailyMixName(sp.name) && sp.name.contains(genre, ignoreCase = true)
                }
                val linkedId = smart?.playlistId
                if (linkedId != null) {
                    val pl = playlistById[linkedId]
                    if (pl != null && !HomeFeedRules.isDailyMixName(pl.name)) {
                        playlistCard(
                            pl,
                            kind = HomeSectionKind.TopMixes,
                            subtitle = "$genre mix",
                        )?.let { add(it) }
                        continue
                    }
                }
                val named = input.allPlaylists.firstOrNull { HomeFeedRules.isGenreMixPlaylistName(it.name, genre) }
                    ?: input.allPlaylists.firstOrNull { it.name.equals("$genre Mix", ignoreCase = true) }
                if (named != null) {
                    playlistCard(
                        named,
                        kind = HomeSectionKind.TopMixes,
                        subtitle = "$genre mix",
                    )?.let { add(it) }
                    continue
                }
                val seedArtist = HomeFeedRules.topArtistForGenre(input.history, genre)
                    ?: topArtists.getOrNull(index)?.name
                    ?: topArtists.firstOrNull()?.name
                    ?: continue
                val card = HomeCard(
                    id = "mix-$genre",
                    title = "$genre Mix",
                    subtitle = "Based on your listening",
                    artPath = resolveMixArt(genre, seedArtist, index),
                    playTarget = PlayTarget.Artist(seedArtist),
                    kind = HomeSectionKind.TopMixes,
                )
                if (!registry.hasCard(card.id)) {
                    registry.registerCard(card)
                    add(card)
                }
            }
            val genreMixPool = input.allPlaylists.filter {
                HomeFeedRules.isGenreMixPlaylistName(it.name) && !HomeFeedRules.isDailyMixName(it.name)
            }.sortedByDescending { it.tracks }
            addAll(
                fillPlaylists(genreMixPool + shuffledGeneric, HomeFeedLimits.TOP_MIXES - size, HomeSectionKind.TopMixes) { pl ->
                    if (HomeFeedRules.isGenreMixPlaylistName(pl.name)) "Curated mix" else "${pl.tracks} tracks · Suggested mix"
                },
            )
        }

        val dailyMixes = buildList {
            for (sp in input.smartPlaylists.filter { it.enabled && HomeFeedRules.isDailyMixName(it.name) }) {
                if (size >= HomeFeedLimits.DAILY_MIXES) break
                val pl = sp.playlistId?.let { playlistById[it] } ?: continue
                playlistCard(pl, kind = HomeSectionKind.DailyMixes, subtitle = "Daily mix")?.let { add(it) }
            }
            for (pl in input.allPlaylists.filter { HomeFeedRules.isDailyMixName(it.name) }.sortedByDescending { parseSortDate(it.lastUsed) }) {
                if (size >= HomeFeedLimits.DAILY_MIXES) break
                playlistCard(pl, kind = HomeSectionKind.DailyMixes, subtitle = "Daily mix")?.let { add(it) }
            }
            val mixLike = input.allPlaylists.filter {
                HomeFeedRules.isGenreMixPlaylistName(it.name) || it.name.contains("mix", ignoreCase = true)
            }.sortedByDescending { it.tracks }
            addAll(
                fillPlaylists(mixLike + shuffledGeneric, HomeFeedLimits.DAILY_MIXES - size, HomeSectionKind.DailyMixes) { "Mix playlist" },
            )
        }.distinctBy { it.id }.take(HomeFeedLimits.DAILY_MIXES)

        val recentPlaylists = buildList {
            for (name in recentPlaylistNames) {
                if (size >= HomeFeedLimits.RECENT_PLAYLISTS) break
                val pl = playlistByName[name.lowercase()] ?: continue
                playlistCard(pl, artByPlaylist[name.lowercase()], HomeSectionKind.RecentPlaylists, "Played recently")?.let { add(it) }
            }
            addAll(
                fillPlaylists(
                    input.allPlaylists.sortedByDescending { parseSortDate(it.lastUsed) } + shuffledGeneric,
                    HomeFeedLimits.RECENT_PLAYLISTS - size,
                    HomeSectionKind.RecentPlaylists,
                ) { "From your library" },
            )
        }

        val radioCards = buildRadioCards(input.history, topArtists, topGenres, input.allPlaylists, registry, HomeFeedLimits.RADIO)

        val discoverCandidates = buildList {
            for (pl in input.allPlaylists.filter { HomeFeedRules.isDiscoverName(it.name) }.sortedByDescending { parseSortDate(it.createDate) }) {
                if (size >= HomeFeedLimits.DISCOVER) break
                playlistCard(pl, kind = HomeSectionKind.Discover, subtitle = "Discover Weekly")?.let { add(it) }
            }
            for (sp in input.smartPlaylists.filter { it.enabled }) {
                if (size >= HomeFeedLimits.DISCOVER) break
                val pl = sp.playlistId?.let { playlistById[it] } ?: continue
                val subtitle = if (HomeFeedRules.isDiscoverName(sp.name)) "New to you" else "Smart playlist"
                playlistCard(pl, kind = HomeSectionKind.Discover, subtitle = subtitle)?.let { add(it) }
            }
            val large = input.allPlaylists.filter { !HomeFeedRules.isSpecialHomePlaylistName(it.name) }.sortedByDescending { it.tracks }
            addAll(
                fillPlaylists(large + shuffledGeneric, HomeFeedLimits.DISCOVER - size, HomeSectionKind.Discover) {
                    "${it.tracks} tracks · Discover"
                },
            )
        }.distinctBy { it.id }.take(HomeFeedLimits.DISCOVER)

        val morePlaylists = fillPlaylists(
            shuffledGeneric + input.allPlaylists.sortedByDescending { it.tracks },
            HomeFeedLimits.MORE_PLAYLISTS,
            HomeSectionKind.RecentPlaylists,
        ) { "${it.tracks} tracks · Suggested for you" }

        val sections = listOfNotNull(
            section("jump-back-in", "Jump back in", HomeSectionKind.JumpBackIn, jumpBackIn),
            section("favorites", "Your favorites", HomeSectionKind.Favorites, favoriteCards),
            section("top-mixes", "Your top mixes", HomeSectionKind.TopMixes, genreMixes),
            section("daily-mixes", "Daily mixes", HomeSectionKind.DailyMixes, dailyMixes),
            section("recent-playlists", "Recent playlists", HomeSectionKind.RecentPlaylists, recentPlaylists),
            section("radio", "Radio", HomeSectionKind.Radio, radioCards),
            section("discover", "Discover", HomeSectionKind.Discover, discoverCandidates),
            section("more-playlists", "More playlists", HomeSectionKind.RecentPlaylists, morePlaylists),
        )

        return HomeFeed(sections)
    }

    private fun section(id: String, title: String, kind: HomeSectionKind, cards: List<HomeCard>): HomeSection? {
        if (cards.isEmpty()) return null
        return HomeSection(id, title, kind, cards)
    }

    private fun parseSortDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrElse {
            runCatching { java.time.ZonedDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)
        }
    }

    private fun artPathForPlaylistSeed(history: List<StreamHistoryItem>, playlistName: String): String? =
        history.firstOrNull { row ->
            row.filepath != null && row.playlist.equals(playlistName, ignoreCase = true)
        }?.filepath

    private fun dashboardJumpCards(
        dashboard: DashboardQuickResponse?,
        playlistByName: Map<String, PlaylistSummary>,
        artByPlaylist: Map<String, String>,
    ): List<HomeCard> = dashboard?.recent.orEmpty().mapNotNull { item ->
        val playlistName = item.playlist?.trim()?.takeIf { it.isNotBlank() }
        if (playlistName != null) {
            val pl = playlistByName[playlistName.lowercase()] ?: return@mapNotNull null
            return@mapNotNull HomeCard(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = item.artist ?: "Recently played",
                artPath = artByPlaylist[playlistName.lowercase()] ?: item.path,
                playlistId = pl.id,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                kind = HomeSectionKind.JumpBackIn,
            )
        }
        val path = item.path?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = item.track?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        HomeCard(
            id = "dash-${path.hashCode()}",
            title = title,
            subtitle = item.artist ?: "Recently played",
            artPath = path,
            playTarget = PlayTarget.Song(path, title),
            kind = HomeSectionKind.JumpBackIn,
        )
    }

    private fun buildRadioCards(
        history: List<StreamHistoryItem>,
        topArtists: List<CountRow>,
        topGenres: List<CountRow>,
        allPlaylists: List<PlaylistSummary>,
        registry: HomeFeedRegistry,
        limit: Int,
    ): List<HomeCard> = buildList {
        fun resolveRadioArt(preferred: String?, artist: String?, index: Int): String? {
            registry.claimArtPath(preferred)?.let { return it }
            artist?.let { a ->
                registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, registry.usedArtPaths))?.let { return it }
            }
            topArtists.getOrNull(index)?.name?.let { a ->
                registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, a, registry.usedArtPaths))?.let { return it }
            }
            return registry.claimArtPath(HomeFeedRules.nextDistinctArtPath(history, registry.usedArtPaths))
        }

        for ((index, row) in topArtists.take(8).withIndex()) {
            if (size >= limit) break
            val artist = row.name ?: row.label ?: continue
            val card = HomeCard(
                id = "radio-artist-$artist",
                title = "$artist Radio",
                subtitle = "Infinite · artist seed",
                artPath = resolveRadioArt(null, artist, index),
                playTarget = PlayTarget.Radio("$artist Radio", PlayTarget.RadioSeedKind.Artist, artist),
                kind = HomeSectionKind.Radio,
            )
            if (!registry.hasCard(card.id)) {
                registry.registerCard(card)
                add(card)
            }
        }
        val songSeeds = history.filter { it.filepath != null && !it.track.isNullOrBlank() }.distinctBy { it.filepath }.take(4)
        for ((index, row) in songSeeds.withIndex()) {
            if (size >= limit) break
            val title = row.track!!
            val card = HomeCard(
                id = "radio-song-${row.filepath}",
                title = title,
                subtitle = listOfNotNull(row.artist, "Song radio").joinToString(" · "),
                artPath = resolveRadioArt(row.filepath, row.artist, index),
                playTarget = PlayTarget.Radio("$title Radio", PlayTarget.RadioSeedKind.Song, title, row.filepath),
                kind = HomeSectionKind.Radio,
            )
            if (!registry.hasCard(card.id)) {
                registry.registerCard(card)
                add(card)
            }
        }
        for ((index, row) in topGenres.take(6).withIndex()) {
            if (size >= limit) break
            val genre = row.name ?: row.label ?: continue
            val seedArtist = HomeFeedRules.topArtistForGenre(history, genre) ?: topArtists.getOrNull(index)?.name ?: continue
            val card = HomeCard(
                id = "radio-genre-$genre",
                title = "$genre Radio",
                subtitle = "Infinite · genre seed",
                artPath = resolveRadioArt(HomeFeedRules.artPathForGenreDistinct(history, genre, registry.usedArtPaths), seedArtist, index),
                playTarget = PlayTarget.Radio("$genre Radio", PlayTarget.RadioSeedKind.Genre, seedArtist),
                kind = HomeSectionKind.Radio,
            )
            if (!registry.hasCard(card.id)) {
                registry.registerCard(card)
                add(card)
            }
        }
        for (pl in allPlaylists.filter { HomeFeedRules.isExplicitRadioPlaylistName(it.name) }.sortedByDescending { it.tracks }) {
            if (size >= limit) break
            if (!registry.canUsePlaylist(pl)) continue
            val card = HomeCard(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = "Radio station",
                artPath = null,
                playlistId = pl.id,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                kind = HomeSectionKind.Radio,
            )
            registry.registerCard(card)
            add(card)
        }
    }.distinctBy { it.id }.take(limit)
}
