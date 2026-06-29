package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.CountRow
import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.api.dto.RatingItem
import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.ResumeEntry
import com.bockmedia.console.data.api.dto.SmartPlaylist
import com.bockmedia.console.data.api.dto.StreamHistoryItem
object HomeFeedLimits {
    const val JUMP_BACK_IN = 36
    const val RATED_SONGS = 5
    const val TOP_MIXES = 24
    const val MOOD_SECTION_MIN = 12
    /** Mood rows include every keyword-matching playlist; cap only guards runaway libraries. */
    const val MOOD_SECTION_CARDS = 500
    const val BROWSE_GENRES = 16
    const val EXPLORE_THEMES = 24
    const val LIBRARY_GENRE_EXTRAS = 10
    const val DAILY_MIXES = 20
    const val RECENT_PLAYLISTS = 36
    const val RADIO = 24
    const val DISCOVER = 36
    // Catch-all home row — large + daily-rotated so the full library surfaces over time.
    const val MORE_PLAYLISTS = 100
}

data class HomeFeedInput(
    val history: List<StreamHistoryItem>,
    val analytics: AnalyticsResponse?,
    val allPlaylists: List<PlaylistSummary>,
    val smartPlaylists: List<SmartPlaylist>,
    val favorites: List<FavoriteItem>,
    val ratedSongItems: List<RatingItem> = emptyList(),
    val dashboard: DashboardQuickResponse?,
    val libraryGenres: List<GenreItem> = emptyList(),
    val shuffleSeed: Long,
    val continueResume: ResumeEntry? = null,
    val releaseRadarLabel: String? = null,
    val releaseRadarArtPath: String? = null,
    val discoverWeeklyCards: List<HomeCard> = emptyList(),
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
        card.artPath?.takeIf { it.isNotBlank() }?.let { usedArtPaths.add(it) }
        card.playlistId?.let { usedPlaylistIds.add(it) }
        if (card.playlistId != null) usedPlaylistNameKeys.add(card.title.lowercase())
    }

    fun registerMoodCard(card: HomeCard) {
        usedCardIds.add(card.id)
    }

    fun reserveMoodPlaylists(cards: List<HomeCard>) {
        for (card in cards) {
            card.playlistId?.let { usedPlaylistIds.add(it) }
            if (card.playlistId != null) usedPlaylistNameKeys.add(card.title.lowercase())
        }
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
        val topGenres = input.analytics?.topGenres.orEmpty().take(12)
        val topArtists = input.analytics?.topArtists.orEmpty()
        val shuffledGeneric = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, input.shuffleSeed)

        val recentPlaylistNames = linkedSetOf<String>()
        val artByPlaylist = mutableMapOf<String, String>()
        for (row in input.history) {
            row.playlist?.trim()?.takeIf { it.isNotBlank() && !HomeFeedRules.isAutomationPlaylistName(it) }?.let { name ->
                recentPlaylistNames.add(name)
                row.filepath?.let { artByPlaylist.putIfAbsent(name.lowercase(), it) }
            }
        }

        fun resolvePlaylistArt(pl: PlaylistSummary, genreHint: String? = null): String? = null

        fun playlistCard(
            pl: PlaylistSummary,
            artPath: String? = null,
            kind: HomeSectionKind,
            subtitle: String? = null,
            claim: Boolean = true,
            genreHint: String? = null,
        ): HomeCard? {
            if (HomeFeedRules.isAutomationPlaylistName(pl.name)) return null
            if (claim && !registry.claimPlaylist(pl.id, pl.name)) return null
            // Prefer the playlist's own cover (first track, from /api/playlists) so the tile
            // renders from the cached feed without a per-tile cover lookup.
            val resolvedArt = artPath?.let { registry.claimArtPath(it) } ?: pl.artPath ?: resolvePlaylistArt(pl, genreHint)
            val card = HomeCard(
                id = "pl-${pl.id}",
                title = pl.name,
                subtitle = subtitle ?: "${pl.tracks} tracks",
                artPath = resolvedArt,
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
            HomeFeedRules.matchingLibraryGenreForLabel(genre, input.libraryGenres)
                ?.artPath?.takeIf { it.isNotBlank() }
                ?.let { path ->
                    registry.claimArtPath(path)
                    return path
                }
            HomeFeedRules.artPathForGenreDistinct(input.history, genre, registry.usedArtPaths)
                ?.let { path ->
                    registry.claimArtPath(path)
                    return path
                }
            // Never fall back to seed artist / top-artist history — that paints the wrong album on genre mixes.
            return null
        }

        val moodSections = HomeMoodSections.all().mapNotNull { mood ->
            val cards = buildMoodSectionCards(
                mood = mood,
                input = input,
                registry = registry,
                playlistById = playlistById,
                topArtists = topArtists,
                resolveMixArt = ::resolveMixArt,
            )
            section("mood-${mood.id}", mood.title, HomeSectionKind.Mood, cards)
        }
        registry.reserveMoodPlaylists(moodSections.flatMap { it.cards })

        val jumpBackIn = buildList {
            input.continueResume?.filepath?.let { path ->
                val resume = input.continueResume
                val pct = if ((resume?.durationMs ?: 0) > 0) {
                    ((resume?.offsetMs ?: 0) * 100 / resume!!.durationMs)
                } else 0
                val card = HomeCard(
                    id = "continue-$path",
                    title = resume?.track ?: "Continue listening",
                    subtitle = "$pct% · ${resume?.artist ?: resume?.context?.name ?: "Pick up where you left off"}",
                    artPath = path,
                    playlistId = resume?.context?.id,
                    playTarget = PlayTarget.Song(path, resume?.track ?: path),
                    kind = HomeSectionKind.JumpBackIn,
                )
                registry.registerCard(card)
                add(card)
            }
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
                playlistCard(pl, kind = HomeSectionKind.JumpBackIn, subtitle = "Recently played")?.let { add(it) }
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

        val ratedSongCards = buildRatedSongCards(input.ratedSongItems, registry)

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
                            genreHint = genre,
                        )?.let { add(it) }
                        continue
                    }
                }
                val named = HomeFeedRules.bestGenreMixPlaylist(input.allPlaylists, genre)
                if (named != null) {
                    // A real genre-mix playlist represents this genre — never synthesize a
                    // "$genre Mix" artist card, even if the playlist already appears elsewhere.
                    playlistCard(
                        named,
                        kind = HomeSectionKind.TopMixes,
                        subtitle = "$genre mix",
                        genreHint = genre,
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
                    playTarget = PlayTarget.Radio(
                        displayTitle = "$genre Mix",
                        seedKind = PlayTarget.RadioSeedKind.Genre,
                        name = seedArtist,
                    ),
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
                HomeFeedRules.isGenreMixPlaylistName(it.name) || HomeFeedRules.hasMixLikeName(it.name)
            }.sortedByDescending { it.tracks }
            addAll(
                fillPlaylists(mixLike + shuffledGeneric, HomeFeedLimits.DAILY_MIXES - size, HomeSectionKind.DailyMixes) { "Mix playlist" },
            )
        }.distinctBy { it.id }.take(HomeFeedLimits.DAILY_MIXES)

        val browseGenres = buildBrowseGenreCards(input.libraryGenres, registry)

        val exploreThemes = buildExploreThemeCards(
            input = input,
            registry = registry,
            playlistById = playlistById,
            topArtists = topArtists,
            topGenres = topGenres,
            resolveMixArt = ::resolveMixArt,
            playlistCard = ::playlistCard,
        )

        val recentPlaylists = buildList {
            for (name in recentPlaylistNames) {
                if (size >= HomeFeedLimits.RECENT_PLAYLISTS) break
                val pl = playlistByName[name.lowercase()] ?: continue
                playlistCard(pl, kind = HomeSectionKind.RecentPlaylists, subtitle = "Played recently")?.let { add(it) }
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

        // Catch-all so no library playlist is permanently hidden from home; the
        // daily shuffle seed rotates which ones lead, and any not shown elsewhere
        // (including genre/mix-named ones whose dedicated rows were full) land here.
        val allRotated = HomeFeedRules.shuffledAllPlaylists(input.allPlaylists, input.shuffleSeed)
        val morePlaylists = fillPlaylists(
            allRotated + input.allPlaylists.sortedByDescending { it.tracks },
            HomeFeedLimits.MORE_PLAYLISTS,
            HomeSectionKind.RecentPlaylists,
        ) { "${it.tracks} tracks · From your library" }

        val releaseRadar = buildList {
            input.releaseRadarLabel?.let { label ->
                val card = HomeCard(
                    id = "release-radar",
                    title = "Release Radar",
                    subtitle = label,
                    artPath = input.releaseRadarArtPath,
                    playTarget = PlayTarget.Radio("New in library", PlayTarget.RadioSeedKind.Genre, "Library", input.releaseRadarArtPath),
                    kind = HomeSectionKind.Discover,
                )
                if (!registry.hasCard(card.id)) {
                    registry.registerCard(card)
                    add(card)
                }
            }
        }

        val sections = listOfNotNull(
            section("jump-back-in", "Jump back in", HomeSectionKind.JumpBackIn, jumpBackIn),
            section("rated-songs", "Rated Songs", HomeSectionKind.RatedSongs, ratedSongCards),
            section("browse-genres", "Browse by genre", HomeSectionKind.BrowseGenres, browseGenres),
            section("top-mixes", "Your top mixes", HomeSectionKind.TopMixes, genreMixes),
        ) + moodSections + listOfNotNull(
            section("release-radar", "Release Radar", HomeSectionKind.Discover, releaseRadar),
            section("discover-weekly", "Discover Weekly", HomeSectionKind.Discover, input.discoverWeeklyCards),
            section("explore-themes", "Explore genres & worlds", HomeSectionKind.ExploreThemes, exploreThemes),
            section("daily-mixes", "New daily mixes", HomeSectionKind.DailyMixes, dailyMixes),
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

    private fun buildBrowseGenreCards(
        libraryGenres: List<GenreItem>,
        registry: HomeFeedRegistry,
    ): List<HomeCard> = buildList {
        for (genre in libraryGenres.sortedByDescending { it.tracks }) {
            if (size >= HomeFeedLimits.BROWSE_GENRES) break
            if (genre.tracks < 8 || genre.name.isBlank()) continue
            val cardId = "browse-genre-${genre.name}"
            if (registry.hasCard(cardId)) continue
            val card = HomeCard(
                id = cardId,
                title = genre.name,
                subtitle = "${genre.tracks} tracks",
                artPath = registry.claimArtPath(genre.artPath),
                playTarget = PlayTarget.Radio(
                    "${genre.name} Radio",
                    PlayTarget.RadioSeedKind.Genre,
                    genre.name,
                ),
                kind = HomeSectionKind.BrowseGenres,
            )
            registry.registerCard(card)
            add(card)
        }
    }

    private fun buildRatedSongCards(
        ratedItems: List<RatingItem>,
        registry: HomeFeedRegistry,
    ): List<HomeCard> = buildList {
        val byStar = ratedItems
            .filter { it.kind == "song" && it.stars in 1..5 }
            .groupBy { it.stars }
        for (stars in RatedSongPlaylists.starLevelsDescending) {
            val songs = byStar[stars].orEmpty()
            if (songs.isEmpty()) continue
            val playlistId = RatedSongPlaylists.id(stars)
            val title = RatedSongPlaylists.title(stars)
            val card = HomeCard(
                id = "rated-$stars",
                title = title,
                subtitle = "${songs.size} tracks",
                artPath = songs.firstOrNull()?.id,
                playlistId = playlistId,
                playTarget = RatedSongPlaylists.playTarget(stars),
                kind = HomeSectionKind.RatedSongs,
            )
            if (!registry.hasCard(card.id)) {
                registry.registerCard(card)
                add(card)
            }
        }
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
            if (HomeFeedRules.isAutomationPlaylistName(playlistName)) return@mapNotNull null
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

    private fun buildExploreThemeCards(
        input: HomeFeedInput,
        registry: HomeFeedRegistry,
        playlistById: Map<String, PlaylistSummary>,
        topArtists: List<CountRow>,
        topGenres: List<CountRow>,
        resolveMixArt: (genre: String, artist: String?, index: Int) -> String?,
        playlistCard: (
            pl: PlaylistSummary,
            artPath: String?,
            kind: HomeSectionKind,
            subtitle: String?,
            claim: Boolean,
        ) -> HomeCard?,
    ): List<HomeCard> = buildList {
        val coveredGenreKeys = mutableSetOf<String>()
        val themes = HomeThemeCatalog.themesForDay(input.shuffleSeed)

        val addedIds = mutableSetOf<String>()

        fun registerThemeCard(card: HomeCard) {
            if (card.id in addedIds) return
            if (!registry.hasCard(card.id)) registry.registerCard(card)
            addedIds.add(card.id)
            add(card)
        }

        for ((index, theme) in themes.withIndex()) {
            if (size >= HomeFeedLimits.EXPLORE_THEMES) break

            val playlistMatch = input.allPlaylists
                .filter { HomeFeedRules.playlistMatchesTheme(it, theme) && it.tracks > 0 }
                .maxWithOrNull(
                    compareByDescending<PlaylistSummary> { HomeFeedRules.playlistThemeScore(it, theme) }
                        .thenByDescending { it.tracks },
                )
            if (playlistMatch != null) {
                playlistCard(
                    playlistMatch,
                    null,
                    HomeSectionKind.ExploreThemes,
                    theme.subtitle,
                    true,
                )?.let { registerThemeCard(it) }
                continue
            }

            val smart = input.smartPlaylists.firstOrNull { sp ->
                sp.enabled && HomeFeedRules.playlistMatchesTheme(sp.name, theme)
            }
            val smartPlaylist = smart?.playlistId?.let { playlistById[it] }
            if (smartPlaylist != null) {
                playlistCard(
                    smartPlaylist,
                    null,
                    HomeSectionKind.ExploreThemes,
                    theme.subtitle,
                    true,
                )?.let { registerThemeCard(it) }
                continue
            }

            val libraryGenre = HomeFeedRules.matchingLibraryGenre(theme, input.libraryGenres)
            val analyticsGenre = topGenres.firstOrNull { row ->
                val name = row.name ?: row.label ?: return@firstOrNull false
                HomeFeedRules.genreMatchesTheme(name, theme)
            }?.let { it.name ?: it.label }
            val genreLabel = libraryGenre ?: analyticsGenre

            if (genreLabel != null) {
                coveredGenreKeys.add(genreLabel.lowercase())
                val libraryItem = input.libraryGenres.firstOrNull { it.name.equals(genreLabel, ignoreCase = true) }
                val seedArtist = HomeFeedRules.topArtistForGenre(input.history, genreLabel)
                    ?: HomeFeedRules.topArtistForTheme(input.history, theme)
                    ?: topArtists.getOrNull(index)?.name
                    ?: topArtists.firstOrNull()?.name
                    ?: genreLabel
                val card = HomeCard(
                    id = "theme-${theme.id}",
                    title = theme.title,
                    subtitle = theme.subtitle,
                    artPath = libraryItem?.artPath ?: resolveMixArt(genreLabel, seedArtist, index),
                    playTarget = PlayTarget.Artist(seedArtist),
                    kind = HomeSectionKind.ExploreThemes,
                )
                registerThemeCard(card)
                continue
            }

            val seedArtist = HomeFeedRules.topArtistForTheme(input.history, theme)
                ?: topArtists.getOrNull(index)?.name
            if (seedArtist != null) {
                val card = HomeCard(
                    id = "theme-${theme.id}",
                    title = theme.title,
                    subtitle = theme.subtitle,
                    artPath = resolveMixArt(theme.title, seedArtist, index),
                    playTarget = PlayTarget.Artist(seedArtist),
                    kind = HomeSectionKind.ExploreThemes,
                )
                registerThemeCard(card)
                continue
            }

            val fallbackSeed = topArtists.firstOrNull()?.name
                ?: input.libraryGenres.firstOrNull()?.name
                ?: "Library"
            val card = HomeCard(
                id = "theme-${theme.id}",
                title = theme.title,
                subtitle = theme.subtitle,
                artPath = resolveMixArt(theme.title, fallbackSeed, index),
                playTarget = PlayTarget.Radio(
                    "${theme.title} Radio",
                    PlayTarget.RadioSeedKind.Genre,
                    fallbackSeed,
                ),
                kind = HomeSectionKind.ExploreThemes,
            )
            registerThemeCard(card)
        }
    }.distinctBy { it.id }

    private fun buildMoodSectionCards(
        mood: HomeMoodSection,
        input: HomeFeedInput,
        registry: HomeFeedRegistry,
        playlistById: Map<String, PlaylistSummary>,
        topArtists: List<CountRow>,
        resolveMixArt: (genre: String, artist: String?, index: Int) -> String?,
    ): List<HomeCard> {
        val theme = mood.theme
        val kind = HomeSectionKind.Mood
        val limit = HomeFeedLimits.MOOD_SECTION_CARDS
        val cards = mutableListOf<HomeCard>()

        fun addCard(card: HomeCard?) {
            if (card == null || registry.hasCard(card.id)) return
            if (cards.any { it.id == card.id }) return
            registry.registerMoodCard(card)
            cards.add(card)
        }

        fun moodPlaylistCard(pl: PlaylistSummary, subtitle: String): HomeCard? {
            if (pl.tracks <= 0) return null
            val cardId = "mood-${mood.id}-pl-${pl.id}"
            if (registry.hasCard(cardId)) return null
            return HomeCard(
                id = cardId,
                title = pl.name,
                subtitle = subtitle,
                artPath = pl.artPath,
                playlistId = pl.id,
                playTarget = PlayTarget.Playlist(pl.id, pl.name),
                kind = kind,
            )
        }

        for (pl in HomeFeedRules.playlistsForMoodSection(input.allPlaylists, theme)) {
            if (cards.size >= limit) break
            addCard(moodPlaylistCard(pl, mood.theme.subtitle))
        }

        for (sp in input.smartPlaylists.filter { it.enabled && HomeFeedRules.playlistMatchesMoodSection(it.name, theme) }) {
            if (cards.size >= limit) break
            val pl = sp.playlistId?.let { playlistById[it] } ?: continue
            addCard(moodPlaylistCard(pl, mood.theme.subtitle))
        }

        if (cards.isEmpty()) {
            val seedArtist = HomeFeedRules.topArtistForTheme(input.history, theme)
                ?: topArtists.firstOrNull()?.name
                ?: mood.title
            addCard(
                HomeCard(
                    id = "mood-${mood.id}-fallback",
                    title = mood.theme.title,
                    subtitle = mood.theme.subtitle,
                    artPath = resolveMixArt(mood.title, seedArtist, mood.id.hashCode()),
                    playTarget = PlayTarget.Radio(
                        "${mood.title} Radio",
                        PlayTarget.RadioSeedKind.Genre,
                        seedArtist,
                    ),
                    kind = kind,
                ),
            )
        }

        return cards.distinctBy { it.id }.take(limit)
    }
}
