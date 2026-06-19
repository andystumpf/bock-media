package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.toPlayTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

enum class HomeFilter(val label: String) {
    All("All"),
    Playlists("Playlists"),
    Mixes("Mixes"),
    Radio("Radio"),
    Discover("Discover"),
    Offline("Downloads"),
}

enum class HomeSectionKind {
    JumpBackIn,
    Favorites,
    TopMixes,
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

object HomeFeedLoader {
    private const val HISTORY_LIMIT = 150
    private const val PLAYLIST_LIMIT = 500
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
        val genresDef = async { runCatching { repository.genres(limit = 40) }.getOrNull() }

        val history = historyDef.await()?.items.orEmpty()
        val analytics = analyticsDef.await()
        val allPlaylists = playlistsDef.await()?.items.orEmpty()
        val smartPlaylists = smartDef.await()?.items.orEmpty()
        val dashboard = dashboardDef.await()
        val favorites = dashboard?.favorites?.takeIf { it.isNotEmpty() } ?: favoritesDef.await()
        val libraryGenres = genresDef.await()?.items.orEmpty()

        val shuffleSeed = LocalDate.now().dayOfYear.toLong()
        val input = HomeFeedInput(
            history = history,
            analytics = analytics,
            allPlaylists = allPlaylists,
            smartPlaylists = smartPlaylists,
            favorites = favorites,
            dashboard = dashboard,
            libraryGenres = libraryGenres,
            shuffleSeed = shuffleSeed,
        )
        val composed = HomeFeedComposer.compose(input)
        HomeTileRotation.apply(composed, input)
    }
}

fun HomeFilter.matches(kind: HomeSectionKind): Boolean = when (this) {
    HomeFilter.All -> kind != HomeSectionKind.Offline
    HomeFilter.Offline -> false
    HomeFilter.Playlists -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists || kind == HomeSectionKind.Favorites
    HomeFilter.Mixes -> kind == HomeSectionKind.TopMixes ||
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
