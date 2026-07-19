package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.api.dto.FavoriteItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import com.bockmedia.console.local.HomeSectionPinsStore
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.OfflineDownloadSync
import com.bockmedia.console.local.toPlayTarget
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
    Decade,
    DailyMixes,
    RecentlyCreated,
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

/** Playlist tiles use long-press to play; skip the green overlay FAB. */
fun HomeCard.showsHomePlayOverlay(sectionKind: HomeSectionKind, sectionId: String): Boolean {
    if (sectionKind == HomeSectionKind.Radio) return false
    if (sectionKind == HomeSectionKind.BrowseGenres) return false
    if (sectionId == "followed-releases") return false
    return playTarget !is PlayTarget.Playlist
}

/** Hide download badge on browse/radio rows and followed-artist releases; playlists use long-press. */
fun HomeCard.showsHomeDownloadOverlay(sectionKind: HomeSectionKind, sectionId: String): Boolean {
    if (sectionKind == HomeSectionKind.Radio) return false
    if (sectionKind == HomeSectionKind.BrowseGenres) return false
    if (sectionId == "followed-releases") return false
    return playTarget !is PlayTarget.Playlist
}

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
    sections.any { it.id == "recently-created" }
        && sections.any { it.id == "more-playlists" || it.id == "browse-genres" || it.id == "recent-playlists" }

/**
 * True when a cached feed is safe to paint (layout matches app + profile-specific rows).
 * Empty rated-songs section is omitted by the composer; usability must use [hasRatedSongs],
 * not section presence alone.
 */
fun HomeFeed.isUsableHomeCache(activeProfileLinked: Boolean, hasRatedSongs: Boolean? = null): Boolean {
    if (!hasCurrentHomeLayout()) return false
    if (!activeProfileLinked) return true
    if (sections.any { it.kind == HomeSectionKind.RatedSongs }) return true
    return hasRatedSongs != true
}

/** Invalidate in-memory home cache when profile-linked ratings exist but the section was dropped. */
fun shouldRefreshHomeForProfile(activeProfileLinked: Boolean, feed: HomeFeed?, hasRatedSongs: Boolean?): Boolean {
    if (!activeProfileLinked || feed == null) return false
    if (feed.sections.any { it.kind == HomeSectionKind.RatedSongs }) return false
    return hasRatedSongs == true
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
    private const val PLAYLIST_LIMIT = 500
    private const val GENRE_LIMIT = 80

    suspend fun load(context: Context, repository: BockMediaRepository): HomeFeed {
        runCatching { ClientPrefsSync.ensureProfileLinked(context) }
        val home = runCatching {
            repository.home(
                deferred = true,
                includeRatings = true,
                playlistLimit = PLAYLIST_LIMIT,
                genreLimit = GENRE_LIMIT,
            )
        }.getOrNull()

        val analytics = home?.listeningSummary ?: home?.analytics
        val ratedItems = home?.ratings?.items.orEmpty()
        val history = home?.history?.items.orEmpty()
        home?.history?.let { SessionDataStore.putHistory(it) }
        home?.dashboard?.let { SessionDataStore.putDashboard(it) }
        analytics?.let { SessionDataStore.putAnalytics(it) }
        val memberKey = ActiveProfileStore.activeMemberId(context).orEmpty()
        val allPlaylists = home?.playlists?.items.orEmpty().also { items ->
            home?.playlists?.let { SessionDataStore.putPlaylists(memberKey, it) }
            items.forEach { pl ->
                pl.artPath?.takeIf { it.isNotBlank() }?.let { HomeArtworkCache.storePlaylistPath(pl.id, it) }
            }
        }
        val smartPlaylists = home?.smartPlaylists?.items.orEmpty()
        val dashboard = home?.dashboard
        val favorites = dashboard?.favorites?.takeIf { it.isNotEmpty() }
            ?: ratedItems.map { row ->
                FavoriteItem(
                    path = row.id,
                    track = row.title,
                    artist = row.artist,
                    album = row.album,
                )
            }
        val libraryGenres = home?.genres?.items.orEmpty()
        val continueData = home?.`continue`
        val libraryNew = home?.libraryNew
        val followedLibraryNew = home?.followedLibraryNew
        val discoverData = home?.discoverWeekly

        val releaseLabel = libraryNew?.albums?.size?.takeIf { it > 0 }?.let { n ->
            "Added this week · $n album${if (n == 1) "" else "s"}"
        }
        val followedReleaseCards = followedLibraryNew?.albums.orEmpty().take(12).mapNotNull { album ->
            val name = album.album?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val artist = album.artist?.trim().orEmpty()
            HomeCard(
                id = "followed-${artist}-${name}".hashCode().toString(),
                title = name,
                subtitle = buildString {
                    if (artist.isNotEmpty()) append(artist)
                    append(if (isNotEmpty()) " · " else "")
                    append("New in library")
                },
                artPath = album.path,
                playTarget = PlayTarget.Album(name, artist.takeIf { it.isNotEmpty() }),
                kind = HomeSectionKind.Discover,
            )
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
            followedReleaseCards = followedReleaseCards,
            discoverWeeklyCards = discoverCards,
            recentlyCreatedPlaylists = home?.recentlyCreatedPlaylists?.items.orEmpty(),
        )
        val composed = HomeFeedComposer.compose(input)
        val rotated = HomeTileRotation.apply(composed, input)
        val serverPins = home?.homeDefaults?.sectionPins.orEmpty().map {
            HomeSectionPin(
                sectionId = it.sectionId,
                playlistId = it.playlistId,
                playlistName = it.playlistName,
                pinnedAtMs = it.pinnedAtMs,
            )
        }
        val mergedPins = HomeSectionPinsApplier.mergePins(serverPins, HomeSectionPinsStore.load())
        val result = HomeSectionPinsApplier.apply(rotated, mergedPins, allPlaylists)
        HomeFeedCache.setHasRatedSongs(ratedItems.isNotEmpty())
        return result
    }
}

fun HomeFilter.matches(kind: HomeSectionKind): Boolean = when (this) {
    HomeFilter.All -> kind != HomeSectionKind.Offline
    HomeFilter.Offline -> false
    HomeFilter.Recents -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists || kind == HomeSectionKind.RecentlyCreated
    HomeFilter.Playlists -> kind == HomeSectionKind.JumpBackIn || kind == HomeSectionKind.RecentPlaylists || kind == HomeSectionKind.RecentlyCreated || kind == HomeSectionKind.Favorites || kind == HomeSectionKind.RatedSongs || kind == HomeSectionKind.Decade || kind == HomeSectionKind.BrowseGenres
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
    OfflineDownloadSync.claimOrphansForActiveProfile(context)
    val store = OfflineDownloadStore(context)
    val visibleIds = OfflineDownloadSync.visibleCollectionIds(context)
    val cards = store.listManifests()
        .filter { manifest -> manifest.id in visibleIds && store.isCollectionComplete(manifest) }
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
