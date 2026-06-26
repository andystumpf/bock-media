package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.toPlayTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

enum class HomeFilter(val label: String) {
    All("All"),
    Recents("Recents"),
    Playlists("Playlists"),
    Mixes("Mixes"),
    Radio("Radio"),
    Discover("Discover"),
    Offline("Downloads"),
}

enum class HomeSectionKind {
    JumpBackIn,
    Favorites,
    RatedSongs,
    TopMixes,
    BrowseGenres,
    ExploreThemes,
    Mood,
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

/** Library playlist id when this card opens playlist detail / cover API. */
fun HomeCard.linkedPlaylistId(): String? =
    playlistId ?: (playTarget as? PlayTarget.Playlist)?.id

data class HomeSection(
    val id: String,
    val title: String,
    val kind: HomeSectionKind,
    val cards: List<HomeCard>,
)

data class HomeFeed(
    val sections: List<HomeSection>,
)

fun HomeFeed.hasCurrentHomeLayout(): Boolean =
    sections.count { it.kind == HomeSectionKind.Mood } >= HomeMoodSections.all().size

/** True when a cached feed is safe to paint (layout matches app + profile-specific rows). */
fun HomeFeed.isUsableHomeCache(activeProfileLinked: Boolean): Boolean {
    if (!hasCurrentHomeLayout()) return false
    if (activeProfileLinked && sections.none { it.kind == HomeSectionKind.RatedSongs }) return false
    return true
}

private val homeShortcutMixKinds = setOf(
    HomeSectionKind.TopMixes,
    HomeSectionKind.Mood,
    HomeSectionKind.DailyMixes,
    HomeSectionKind.ExploreThemes,
    HomeSectionKind.RecentPlaylists,
)

/** Quick-access tiles: playlists and mixes only — never albums or individual tracks. */
fun HomeCard.eligibleForHomeShortcut(): Boolean = when (playTarget) {
    is PlayTarget.Album, is PlayTarget.Song -> false
    is PlayTarget.Playlist -> !HomeFeedRules.isAutomationPlaylistName(playTarget.name)
    is PlayTarget.Artist, is PlayTarget.Radio -> kind in homeShortcutMixKinds
    else -> false
}

/** Up to [limit] shortcut tiles: recent playlists first, then backfilled from mix rows. */
fun HomeFeed.homeShortcutCards(limit: Int = 6): List<HomeCard> {
    val jump = sections.firstOrNull { it.kind == HomeSectionKind.JumpBackIn }
        ?.cards
        ?.filter { it.eligibleForHomeShortcut() }
        .orEmpty()
    val seen = jump.map { it.id }.toMutableSet()
    val result = jump.toMutableList()
    if (result.size >= limit) return result.take(limit)
    for (section in sections) {
        if (section.kind !in homeShortcutMixKinds) continue
        for (card in section.cards) {
            if (!card.eligibleForHomeShortcut() || card.id in seen) continue
            result.add(card)
            seen.add(card.id)
            if (result.size >= limit) return result
        }
    }
    return result
}

object HomeFeedLoader {
    private const val HISTORY_LIMIT = 150
    private const val PLAYLIST_LIMIT = 500
    private const val ANALYTICS_TIMEOUT_MS = 4_000L

    suspend fun load(context: Context, repository: BockMediaRepository): HomeFeed = coroutineScope {
        runCatching { ClientPrefsSync.ensureProfileLinked(context) }
        val historyDef = async { runCatching { repository.streamHistory(1, HISTORY_LIMIT) }.getOrNull() }
        val analyticsDef = async {
            withTimeoutOrNull(ANALYTICS_TIMEOUT_MS) {
                runCatching { repository.analytics() }.getOrNull()
            }
        }
        val playlistsDef = async { runCatching { repository.playlists(limit = PLAYLIST_LIMIT) }.getOrNull() }
        val smartDef = async { runCatching { repository.smartPlaylists() }.getOrNull() }
        val favoritesDef = async { runCatching { repository.ratedSongs() }.getOrNull().orEmpty() }
        val dashboardDef = async { runCatching { repository.dashboardQuick() }.getOrNull() }
        val genresDef = async { runCatching { repository.genres(limit = 200) }.getOrNull() }
        val continueDef = async { runCatching { repository.continueListening() }.getOrNull() }
        val newDef = async { runCatching { repository.libraryNew() }.getOrNull() }
        val discoverDef = async { runCatching { repository.discoverWeekly() }.getOrNull() }

        val history = historyDef.await()?.items.orEmpty()
        val analytics = analyticsDef.await()
        val allPlaylists = playlistsDef.await()?.items.orEmpty().also { items ->
            items.forEach { pl ->
                pl.artPath?.takeIf { it.isNotBlank() }?.let { HomeArtworkCache.storePlaylistPath(pl.id, it) }
            }
        }
        val smartPlaylists = smartDef.await()?.items.orEmpty()
        val dashboard = dashboardDef.await()
        val ratedItems = favoritesDef.await()
        val favorites = dashboard?.favorites?.takeIf { it.isNotEmpty() }
            ?: ratedItems.map { row ->
                FavoriteItem(
                    path = row.id,
                    track = row.title,
                    artist = row.artist,
                    album = row.album,
                )
            }
        val libraryGenres = genresDef.await()?.items.orEmpty()
        val continueData = continueDef.await()
        val libraryNew = newDef.await()
        val discoverData = discoverDef.await()

        val releaseLabel = libraryNew?.albums?.size?.takeIf { it > 0 }?.let { n ->
            "Added this week · $n album${if (n == 1) "" else "s"}"
        }
        val discoverCards = discoverData?.sections?.firstOrNull()?.tracks.orEmpty().take(12).mapNotNull { t ->
            val path = t.path ?: return@mapNotNull null
            HomeCard(
                id = "dw-$path",
                title = t.title ?: path,
                subtitle = discoverData?.sections?.firstOrNull()?.reason ?: "Discover Weekly",
                artPath = path,
                playTarget = PlayTarget.Song(path, t.title ?: path),
                kind = HomeSectionKind.Discover,
            )
        }

        val shuffleSeed = LocalDate.now().dayOfYear.toLong()
        val input = HomeFeedInput(
            history = history,
            analytics = analytics,
            allPlaylists = allPlaylists,
            smartPlaylists = smartPlaylists,
            favorites = favorites,
            ratedSongItems = ratedItems,
            dashboard = dashboard,
            libraryGenres = libraryGenres,
            shuffleSeed = shuffleSeed,
            continueResume = continueData?.resume,
            releaseRadarLabel = releaseLabel,
            releaseRadarArtPath = libraryNew?.albums?.firstOrNull()?.path,
            discoverWeeklyCards = discoverCards,
        )
        val composed = HomeFeedComposer.compose(input)
        HomeTileRotation.apply(composed, input)
    }
}

fun HomeFilter.matches(kind: HomeSectionKind): Boolean = when (this) {
    HomeFilter.All -> kind != HomeSectionKind.Offline
    HomeFilter.Offline -> false
    HomeFilter.Recents -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists
    HomeFilter.Playlists -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists || kind == HomeSectionKind.Favorites || kind == HomeSectionKind.RatedSongs
    HomeFilter.Mixes -> kind == HomeSectionKind.TopMixes ||
        kind == HomeSectionKind.BrowseGenres ||
        kind == HomeSectionKind.ExploreThemes ||
        kind == HomeSectionKind.Mood ||
        kind == HomeSectionKind.DailyMixes
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
