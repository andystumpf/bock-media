package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.GenreItem
import com.bockmedia.console.data.api.dto.PlaylistSummary
import com.bockmedia.console.data.api.dto.StreamHistoryItem
import kotlin.random.Random

/**
 * Client-side rules for Spotify-style home rows (until a dedicated /api/home exists).
 *
 * Jump back in — recent listening (playlists, albums, artists) plus recently added playlists.
 * Top mixes — genre / era style mixes from your library and listening stats (familiar-heavy).
 * Daily mixes — genre clusters (smart or named daily playlists), favorites blended with similar tracks.
 * Recent playlists — any playlist you've played, ordered by recency (not “created by you” only).
 * Radio — infinite shuffle seeded from one artist, song, or genre (not finite mix playlists).
 * Discover — new-to-you leaning playlists and artists you haven't played recently.
 */
object HomeFeedRules {
    private val dailyMixPattern = Regex("(?i)daily mix|daylist")
    private val discoverPattern = Regex("(?i)discover weekly|new release|fresh find|new to you")
    /** Word-boundary mix markers — avoids false positives like "Generation" (era) or "Remix" substring-only. */
    private val genreMixPattern = Regex(
        "(?i)\\bmix\\b|\\bmixes\\b|\\bremix\\b|\\bremixes\\b|essentials|" +
            "\\bdecade\\b|\\bera\\b|\\bhits\\b|\\bparty\\b|\\bfocus\\b|\\bfavorites\\b",
    )
    private val mixLikeNamePattern = Regex("(?i)\\bmix\\b|\\bmixes\\b|\\bremix\\b|\\bremixes\\b")
    private val explicitRadioPlaylistPattern = Regex("(?i)\\bradio\\b|\\bstation\\b")
    private val mixLikePlaylistPattern = Regex("(?i)\\bmix\\b|daily|discover weekly|essentials|station")

    fun isDailyMixName(name: String): Boolean = dailyMixPattern.containsMatchIn(name)

    fun isDiscoverName(name: String): Boolean = discoverPattern.containsMatchIn(name)

    fun isGenreMixPlaylistName(name: String, genre: String? = null): Boolean {
        if (isDailyMixName(name) || isDiscoverName(name)) return false
        if (!genreMixPattern.containsMatchIn(name)) return false
        return genre == null || nameContainsGenre(name, genre)
    }

    /** True when the playlist name looks mix/remix-like (not bare "Remix" substring inside unrelated words). */
    fun hasMixLikeName(name: String): Boolean = mixLikeNamePattern.containsMatchIn(name)

    fun nameContainsGenre(name: String, genre: String): Boolean {
        val g = genre.trim()
        if (g.isBlank()) return false
        if (name.contains(g, ignoreCase = true)) return true
        // Multi-word genres: require every token to appear (e.g. "Hip Hop" in playlist title).
        val tokens = g.split(Regex("\\s+")).filter { it.length > 1 }
        return tokens.size > 1 && tokens.all { name.contains(it, ignoreCase = true) }
    }

    /** Best library playlist for a top-genre mix row — prefers exact "${genre} Mix" over loose matches. */
    fun bestGenreMixPlaylist(all: List<PlaylistSummary>, genre: String): PlaylistSummary? {
        val g = genre.trim()
        if (g.isBlank()) return null
        return all.filter { it.tracks > 0 && isGenreMixPlaylistName(it.name, g) }
            .maxByOrNull { genreMixNameScore(it.name, g) * 10_000 + it.tracks }
    }

    private fun genreMixNameScore(name: String, genre: String): Int {
        var score = 0
        if (name.equals("$genre Mix", ignoreCase = true)) score += 100
        if (name.startsWith(genre, ignoreCase = true)) score += 40
        if (Regex("(?i)\\b${Regex.escape(genre)}\\b").containsMatchIn(name)) score += 30
        else if (name.contains(genre, ignoreCase = true)) score += 10
        return score
    }

    /** Named playlist stations only — not “Rock Mix” / “Daily Mix” style rows. */
    fun isExplicitRadioPlaylistName(name: String): Boolean {
        if (mixLikePlaylistPattern.containsMatchIn(name) && !explicitRadioPlaylistPattern.containsMatchIn(name)) {
            return false
        }
        return explicitRadioPlaylistPattern.containsMatchIn(name)
    }

    fun historyMatchesGenre(row: StreamHistoryItem, genre: String): Boolean {
        val haystack = listOfNotNull(row.sourceLabel, row.playlist, row.album, row.artist)
        return haystack.any { it.contains(genre, ignoreCase = true) }
    }

    fun artPathForArtist(history: List<StreamHistoryItem>, artist: String): String? =
        artPathForArtistDistinct(history, artist, emptySet())

    fun artPathForGenre(history: List<StreamHistoryItem>, genre: String): String? =
        artPathForGenreDistinct(history, genre, emptySet())

    fun artPathForArtistDistinct(
        history: List<StreamHistoryItem>,
        artist: String,
        used: Set<String>,
    ): String? = history.firstOrNull { row ->
        row.filepath != null &&
            row.filepath !in used &&
            row.artist.equals(artist, ignoreCase = true)
    }?.filepath

    fun artPathForGenreDistinct(
        history: List<StreamHistoryItem>,
        genre: String,
        used: Set<String>,
    ): String? = history.firstOrNull { row ->
        row.filepath != null &&
            row.filepath !in used &&
            historyMatchesGenre(row, genre)
    }?.filepath

    fun artPathForPlaylistDistinct(
        history: List<StreamHistoryItem>,
        playlistName: String,
        used: Set<String>,
    ): String? = history.firstOrNull { row ->
        row.filepath != null &&
            row.filepath !in used &&
            row.playlist.equals(playlistName, ignoreCase = true)
    }?.filepath

    /** Next unused cover from stream history — avoids duplicate tiles when genre/artist data is sparse. */
    fun nextDistinctArtPath(history: List<StreamHistoryItem>, used: Set<String>): String? =
        history.firstOrNull { row ->
            row.filepath != null && row.filepath !in used
        }?.filepath

    fun topArtistForGenre(history: List<StreamHistoryItem>, genre: String): String? =
        history
            .filter { row -> row.artist != null && historyMatchesGenre(row, genre) }
            .groupingBy { it.artist!!.lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.let { key -> history.firstOrNull { it.artist.equals(key, true) }?.artist }

    fun matchesKeywords(text: String, keywords: List<String>): Boolean =
        keywords.any { keyword -> text.contains(keyword, ignoreCase = true) }

    fun playlistSearchText(playlist: PlaylistSummary): String =
        listOfNotNull(playlist.name, playlist.sourceName, playlist.source).joinToString(" ")

    fun playlistMatchesTheme(playlist: PlaylistSummary, theme: HomeTheme): Boolean =
        playlistThemeScore(playlist, theme) > 0

    fun playlistMatchesTheme(name: String, theme: HomeTheme): Boolean =
        matchesKeywords(name, theme.playlistKeywords)

    fun genreMatchesTheme(name: String, theme: HomeTheme): Boolean =
        matchesKeywords(name, theme.genreKeywords)

    fun historyMatchesTheme(row: StreamHistoryItem, theme: HomeTheme): Boolean {
        val haystack = listOfNotNull(row.sourceLabel, row.playlist, row.album, row.artist)
        val keywords = theme.playlistKeywords + theme.genreKeywords
        return haystack.any { matchesKeywords(it, keywords) }
    }

    fun topArtistForTheme(history: List<StreamHistoryItem>, theme: HomeTheme): String? =
        history
            .filter { row -> row.artist != null && historyMatchesTheme(row, theme) }
            .groupingBy { it.artist!!.lowercase() }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.let { key -> history.firstOrNull { it.artist.equals(key, true) }?.artist }

    fun matchingLibraryGenre(theme: HomeTheme, libraryGenres: List<GenreItem>): String? =
        libraryGenres.firstOrNull { genreMatchesTheme(it.name, theme) }?.name

    /** Genre label from a synthetic home tile title (e.g. "Classical Era Mix" → "Classical Era"). */
    fun mixGenreLabel(title: String): String? =
        title.removeSuffix(" Mix").takeIf { it.isNotBlank() && it.length < title.length }

    /** Genre label from a genre-radio tile (e.g. "Jazz Radio" → "Jazz"). */
    fun genreRadioLabel(displayTitle: String): String? =
        displayTitle.removeSuffix(" Radio").takeIf { it.isNotBlank() && it.length < displayTitle.length }

    /** Match analytics / mix label (e.g. "Classical Era") to a library genre row for stable cover art. */
    fun matchingLibraryGenreForLabel(label: String, libraryGenres: List<GenreItem>): GenreItem? {
        val g = label.trim()
        if (g.isBlank()) return null
        libraryGenres.firstOrNull { it.name.equals(g, ignoreCase = true) }?.let { return it }
        return libraryGenres.firstOrNull {
            nameContainsGenre(it.name, g) || nameContainsGenre(g, it.name)
        }
    }

    fun playlistThemeScore(playlist: PlaylistSummary, theme: HomeTheme): Int =
        playlistThemeScore(playlistSearchText(playlist), theme)

    fun playlistThemeScore(name: String, theme: HomeTheme): Int {
        val haystack = name.lowercase()
        var score = 0
        for (keyword in theme.playlistKeywords) {
            if (haystack.contains(keyword.lowercase())) score += 10
        }
        for (keyword in theme.genreKeywords) {
            if (haystack.contains(keyword.lowercase())) score += 4
        }
        return score
    }

    fun playlistMatchesMoodSection(playlist: PlaylistSummary, theme: HomeTheme): Boolean =
        matchesKeywords(playlistSearchText(playlist), theme.playlistKeywords)

    fun playlistMatchesMoodSection(name: String, theme: HomeTheme): Boolean =
        matchesKeywords(name, theme.playlistKeywords)

    fun playlistsForMoodSection(all: List<PlaylistSummary>, theme: HomeTheme): List<PlaylistSummary> =
        all
            .filter { it.tracks > 0 && playlistMatchesMoodSection(it, theme) }
            .sortedWith(
                compareByDescending<PlaylistSummary> { playlistKeywordScore(it, theme) }
                    .thenBy { it.name.lowercase() },
            )

    private fun playlistKeywordScore(playlist: PlaylistSummary, theme: HomeTheme): Int {
        val haystack = playlistSearchText(playlist).lowercase()
        return theme.playlistKeywords.count { haystack.contains(it.lowercase()) }
    }

    fun playlistsForTheme(all: List<PlaylistSummary>, theme: HomeTheme): List<PlaylistSummary> =
        all
            .filter { it.tracks > 0 && playlistThemeScore(it, theme) > 0 }
            .sortedWith(
                compareByDescending<PlaylistSummary> { playlistThemeScore(it, theme) }
                    .thenByDescending { it.tracks },
            )

    private val decadePatterns: Map<String, List<Regex>> = mapOf(
        "60s" to listOf(
            Regex("(?i)\\b60'?s\\b"),
            Regex("(?i)'60s"),
            Regex("(?i)\\bsixties\\b"),
            Regex("(?i)\\b1960s\\b"),
            Regex("(?i)\\b196[0-9]\\b"),
        ),
        "70s" to listOf(
            Regex("(?i)\\b70'?s\\b"),
            Regex("(?i)'70s"),
            Regex("(?i)\\bseventies\\b"),
            Regex("(?i)\\b1970s\\b"),
            Regex("(?i)\\b197[0-9]\\b"),
        ),
        "80s" to listOf(
            Regex("(?i)\\b80'?s\\b"),
            Regex("(?i)'80s"),
            Regex("(?i)\\beighties\\b"),
            Regex("(?i)\\b1980s\\b"),
            Regex("(?i)\\b198[0-9]\\b"),
        ),
        "90s" to listOf(
            Regex("(?i)\\b90'?s\\b"),
            Regex("(?i)'90s"),
            Regex("(?i)\\bnineties\\b"),
            Regex("(?i)\\b1990s\\b"),
            Regex("(?i)\\b199[0-9]\\b"),
        ),
    )

    fun playlistMatchesDecade(playlist: PlaylistSummary, decadeId: String): Boolean =
        playlistMatchesDecade(playlistSearchText(playlist), decadeId)

    fun playlistMatchesDecade(name: String, decadeId: String): Boolean {
        val patterns = decadePatterns[decadeId] ?: return false
        return patterns.any { it.containsMatchIn(name) }
    }

    /** All library playlists named for this decade; multi-decade names match every listed decade. */
    fun playlistsForDecadeSection(all: List<PlaylistSummary>, decadeId: String): List<PlaylistSummary> =
        all
            .filter { it.tracks > 0 && playlistMatchesDecade(it, decadeId) }
            .sortedBy { it.name.lowercase() }

    fun isSpecialHomePlaylistName(name: String): Boolean =
        isDailyMixName(name) || isDiscoverName(name) || isGenreMixPlaylistName(name) ||
            isExplicitRadioPlaylistName(name) || isAutomationPlaylistName(name)

    /** Scheduled automation playlists — excluded from recents, mixes, and shortcut tiles. */
    fun isAutomationPlaylistName(name: String): Boolean =
        name.trim().startsWith("Automations", ignoreCase = true)

    fun browsablePlaylists(all: List<PlaylistSummary>): List<PlaylistSummary> =
        all.filter { it.tracks > 0 && !isSpecialHomePlaylistName(it.name) }

    fun shuffledBrowsablePlaylists(all: List<PlaylistSummary>, seed: Long): List<PlaylistSummary> {
        val safeSeed = if (seed == 0L) 0x4d595449L else seed
        return browsablePlaylists(all).shuffled(Random(safeSeed))
    }

    /**
     * Every playlist eligible for the home catch-all row: anything with tracks,
     * excluding scheduled automations and the server's auto-generated daily mixes
     * (those have their own row). Special genre/mix/radio names are kept so they
     * can still surface in the catch-all when their dedicated rows are full.
     */
    fun allHomePlaylists(all: List<PlaylistSummary>): List<PlaylistSummary> =
        all.filter { it.tracks > 0 && !isAutomationPlaylistName(it.name) && !isDailyMixName(it.name) }

    /** Daily-rotated full playlist set so the home teaser eventually surfaces them all. */
    fun shuffledAllPlaylists(all: List<PlaylistSummary>, seed: Long): List<PlaylistSummary> {
        val safeSeed = if (seed == 0L) 0x4d595449L else seed
        return allHomePlaylists(all).shuffled(Random(safeSeed))
    }
}
