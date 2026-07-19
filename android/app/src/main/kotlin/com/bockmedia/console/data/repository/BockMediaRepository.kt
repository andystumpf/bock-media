package com.bockmedia.console.data.repository

import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.ArtworkPaths
import com.bockmedia.console.domain.model.SessionDataStore
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.HomeFeedRules
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.SearchSuggestion
import com.bockmedia.console.ui.components.RatingKind
import com.bockmedia.console.domain.model.SearchSuggestionKind
import com.bockmedia.console.domain.model.filterSearchSongHits
import com.bockmedia.console.media.PlaybackArtwork
import android.content.Context
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock

class ApiException(val code: String, message: String) : Exception(message)

class BockMediaRepository(
    private val apiProvider: suspend () -> BockMediaApi,
    private val baseUrlProvider: suspend () -> String,
    private val preferences: AppPreferences,
    private val clientIdProvider: () -> String = { "" },
    private val memberIdProvider: () -> String? = { null },
) {
    private suspend fun api() = apiProvider()

    private val playlistTrackPathsCache = ConcurrentHashMap<String, List<String>>()
    private val artistCoverPathsCache = ConcurrentHashMap<String, List<String>>()
    private val artistPortraitPathCache = ConcurrentHashMap<String, String>()
    private val artistPortraitMissCache = ConcurrentHashMap.newKeySet<String>()
    private val ratingsCache = ConcurrentHashMap<String, Int>()
    @Volatile private var ratedSongMapCache: Pair<Long, Map<String, Int>>? = null
    private val ratedSongMapTtlMs = 120_000L
    private val albumCoverPathCache = ConcurrentHashMap<String, String>()
    @Volatile private var cachedBaseUrl: String? = null

    /** Bumped when [primeBaseUrl] runs — artwork composables key off this to rebuild URLs. */
    @Volatile var baseUrlEpoch: Int = 0
        private set

    // Shared, short-TTL cache for the full playlist listing so Home, Library, and
    // Search don't each hit /playlists independently on cold launch (perf #7).
    private val playlistsListMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var playlistsListCache: Pair<Long, PlaylistsResponse>? = null
    private val playlistsListTtlMs = 60_000L

    private suspend fun baseUrl(): String {
        val cached = cachedBaseUrl
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        if (cached != null &&
            !(!NetworkReachability.onWifi && AppPreferences.isLanHost(cached, local, external))
        ) {
            return cached
        }
        cachedBaseUrl = null
        return baseUrlProvider().also { cachedBaseUrl = it }
    }

    /** Best-effort base URL for sync artwork URL building (no network). */
    fun peekBaseUrl(): String? {
        val cached = cachedBaseUrl
        val local = preferences.localServerUrlNow()
            ?: BuildConfig.DEFAULT_LOCAL_SERVER_URL.takeIf { it.isNotBlank() }
        val external = preferences.externalServerUrlNow()
            ?: BuildConfig.DEFAULT_EXTERNAL_SERVER_URL.takeIf { it.isNotBlank() }
        val localNorm = local?.let { AppPreferences.normalizeUrl(it) }
        val externalNorm = external?.let { AppPreferences.normalizeUrl(it) }
        if (!NetworkReachability.onWifi) {
            // Cellular: never use a LAN-only cached host.
            if (cached != null && AppPreferences.isLanHost(cached, localNorm, externalNorm)) {
                return externalNorm ?: cached
            }
            return externalNorm ?: cached ?: localNorm
        }
        // Wi‑Fi: prefer LAN. A stale external cache from cellular must not wedge streams.
        if (cached != null && AppPreferences.isLanHost(cached, localNorm, externalNorm)) {
            return cached
        }
        return localNorm
            ?: cached?.takeIf { AppPreferences.isLanHost(it, localNorm, externalNorm) }
    }

    fun primeBaseUrl(url: String?) {
        url?.takeIf { it.isNotBlank() }?.let {
            cachedBaseUrl = AppPreferences.normalizeUrl(it)
            baseUrlEpoch++
        }
    }

    /** Fast cover lookup — reads only the first tracks from the playlist file on the server. */
    suspend fun playlistCoverPath(playlistId: String, variantKey: String = playlistId): String? {
        HomeArtworkCache.playlistPath(playlistId)?.let { return it }
        playlistTrackPathsCache[playlistId]?.firstOrNull()?.let { return it }
        val path = runCatching {
            api().playlistCover(playlistId).path?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return null
        playlistTrackPathsCache[playlistId] = listOf(path)
        HomeArtworkCache.storePlaylistPath(playlistId, path)
        return path
    }

    suspend fun prefetchPlaylistCoverPaths(ids: Collection<String>) {
        val missing = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .filter {
                HomeArtworkCache.playlistPath(it) == null &&
                    HomeArtworkCache.playlistCollagePaths(it).isNullOrEmpty() &&
                    playlistTrackPathsCache[it].isNullOrEmpty()
            }
        if (missing.isEmpty()) return
        val response = runCatching {
            api().playlistCoversBatch(PlaylistCoversBatchRequest(missing))
        }.getOrNull() ?: return
        if (response.collages.isNotEmpty()) {
            HomeArtworkCache.storePlaylistCollages(response.collages)
            response.collages.forEach { (id, paths) -> playlistTrackPathsCache[id] = paths }
        }
        if (response.covers.isNotEmpty()) {
            HomeArtworkCache.storePlaylistPaths(response.covers)
            response.covers.forEach { (id, path) ->
                playlistTrackPathsCache.putIfAbsent(id, listOf(path))
            }
        }
    }

    suspend fun playlistCollageMediaPaths(playlistId: String): List<String> {
        HomeArtworkCache.playlistCollagePaths(playlistId)?.let { return it }
        playlistTrackPathsCache[playlistId]?.filter { it.isNotBlank() }?.distinct()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        prefetchPlaylistCoverPaths(listOf(playlistId))
        return HomeArtworkCache.playlistCollagePaths(playlistId)
            ?: playlistTrackPathsCache[playlistId].orEmpty().ifEmpty {
                listOfNotNull(playlistCoverPath(playlistId))
            }
    }

    suspend fun artworkUrlForPlaylist(playlistId: String, variantKey: String = playlistId, sizePx: Int? = null): String? =
        playlistCoverPath(playlistId, variantKey)?.let { artworkUrl(it, sizePx) }

    suspend fun artistCoverPathAt(artistName: String, pick: Int = 0): String? {
        val key = artistName.trim().lowercase()
        if (key.isEmpty()) return null
        val paths = artistCoverPathsCache.getOrPut(key) {
            runCatching {
                songs(page = 1, search = artistName, artist = artistName)
                    .items.mapNotNull { it.path?.takeIf { path -> path.isNotBlank() } }
                    .distinct()
            }.getOrDefault(emptyList())
        }
        if (paths.isEmpty()) return null
        return paths[kotlin.math.abs(pick) % paths.size]
    }

    suspend fun artistPortraitPath(artistName: String): String? {
        val key = artistName.trim().lowercase()
        if (key.isEmpty()) return null
        artistPortraitPathCache[key]?.let { return it.takeIf { p -> p.isNotBlank() } }
        if (artistPortraitMissCache.contains(key)) return null
        val path = runCatching {
            api().artistPortrait(artistName).artPath?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (path != null) {
            artistPortraitPathCache[key] = path
            cacheArtistArtPath(artistName, path)
            return path
        }
        artistPortraitMissCache.add(key)
        return null
    }

    suspend fun resolveArtistArtUrl(artistName: String, sizePx: Int? = null): String? =
        artistPortraitPath(artistName)?.let { artworkUrl(it, sizePx) }
            ?: artistCoverPathAt(artistName)?.let { artworkUrl(it, sizePx) }

    suspend fun resolveAlbumArtUrl(albumName: String, artist: String? = null, sizePx: Int? = null): String? =
        albumCoverPath(albumName, artist)?.let { artworkUrl(it, sizePx) }

    private suspend fun albumCoverPath(albumName: String, artist: String? = null): String? {
        val key = "${albumName.trim().lowercase()}|${artist?.trim()?.lowercase().orEmpty()}"
        if (key == "|") return null
        albumCoverPathCache[key]?.let { return it }
        val path = runCatching {
            songs(page = 1, search = albumName, artist = artist, album = albumName, limit = 1)
                .items.firstOrNull()?.path?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: return null
        albumCoverPathCache[key] = path
        return path
    }

    suspend fun cacheArtPath(albumName: String, artist: String?, path: String?) {
        if (path.isNullOrBlank()) return
        val key = "${albumName.trim().lowercase()}|${artist?.trim()?.lowercase().orEmpty()}"
        if (key != "|") albumCoverPathCache[key] = path
    }

    suspend fun cacheArtistArtPath(artistName: String, path: String?) {
        if (path.isNullOrBlank()) return
        val key = artistName.trim().lowercase()
        if (key.isEmpty()) return
        artistCoverPathsCache.compute(key) { _, existing ->
            val list = existing?.toMutableList() ?: mutableListOf()
            if (path !in list) list.add(0, path)
            list
        }
    }

    suspend fun resolveHomeCardArtUrl(
        cardId: String,
        artPath: String?,
        playlistId: String?,
        playTarget: PlayTarget,
        sizePx: Int = ArtworkPaths.TILE_SIZE_PX,
    ): String? {
        val pick = kotlin.math.abs(cardId.hashCode())
        val linkedPlaylistId = playlistId ?: (playTarget as? PlayTarget.Playlist)?.id
        linkedPlaylistId?.let { return artworkUrlForPlaylist(it, it, sizePx) }
        artPath?.let { return artworkUrl(it, sizePx) }
        return when (playTarget) {
            is PlayTarget.Album -> {
                runCatching {
                    val paths = songs(page = 1, search = playTarget.name, artist = playTarget.artist, album = playTarget.name)
                        .items.mapNotNull { it.path?.takeIf { path -> path.isNotBlank() } }
                        .distinct()
                    paths.getOrNull(pick % paths.size.coerceAtLeast(1))
                }.getOrNull()?.let { artworkUrl(it, sizePx) }
            }
            is PlayTarget.Artist -> artistCoverPathAt(playTarget.name, pick)?.let { artworkUrl(it, sizePx) }
            is PlayTarget.Radio -> {
                playTarget.path?.let { return artworkUrl(it, sizePx) }
                if (playTarget.seedKind == PlayTarget.RadioSeedKind.Genre) {
                    val genreLabel = HomeFeedRules.mixGenreLabel(playTarget.displayTitle)
                        ?: HomeFeedRules.genreRadioLabel(playTarget.displayTitle)
                    genreLabel?.let { resolveGenreArtByName(it, sizePx) }?.let { return it }
                    return null
                }
                artistCoverPathAt(playTarget.name, pick)?.let { artworkUrl(it, sizePx) }
            }
            else -> null
        }
    }

    suspend fun resolveOfflineManifestArtUrl(manifest: com.bockmedia.console.local.OfflineCollectionManifest): String? {
        manifest.coverArtPath?.let { return artworkUrl(it) }
        val paths = manifest.tracks.map { it.path }.filter { it.isNotBlank() }.distinct()
        if (paths.isNotEmpty()) {
            val pick = kotlin.math.abs(manifest.id.hashCode()) % paths.size
            return artworkUrl(paths[pick])
        }
        manifest.sourcePlaylistId?.let { return artworkUrlForPlaylist(it, manifest.id) }
        manifest.legacyPlaylistId?.let { return artworkUrlForPlaylist(it, manifest.id) }
        return when (manifest.kind) {
            "artist" -> artistCoverPathAt(manifest.title, manifest.id.hashCode())?.let { artworkUrl(it) }
            "album" -> {
                val artist = manifest.tracks.firstOrNull()?.artist
                runCatching {
                    songs(page = 1, search = manifest.title, artist = artist, album = manifest.title)
                        .items.mapNotNull { it.path?.takeIf { path -> path.isNotBlank() } }
                        .firstOrNull()
                }.getOrNull()?.let { artworkUrl(it) }
            }
            else -> null
        }
    }

    fun clearBaseUrlCache() {
        cachedBaseUrl = null
        baseUrlEpoch++
    }

    fun clearCaches() {
        clearBaseUrlCache()
        playlistTrackPathsCache.clear()
        artistCoverPathsCache.clear()
        artistPortraitPathCache.clear()
        artistPortraitMissCache.clear()
        ratingsCache.clear()
        albumCoverPathCache.clear()
        playlistsListCache = null
    }

    suspend fun testConnection(): Result<HealthResponse> = runCatching { api().health() }

    suspend fun summary() = api().summary()
    suspend fun health() = api().health()
    suspend fun plexSyncStatus() = api().plexSyncStatus()
    suspend fun dashboardQuick(): DashboardQuickResponse {
        SessionDataStore.peekDashboard()?.let { return it }
        return api().dashboardQuick().also { SessionDataStore.putDashboard(it) }
    }

    suspend fun home(
        deferred: Boolean = true,
        includeRatings: Boolean = false,
        playlistLimit: Int = 500,
        genreLimit: Int = 40,
        historyLimit: Int = 150,
    ): HomeResponse = api().home(
        deferred = if (deferred) "1" else "0",
        includeRatings = if (includeRatings) "1" else null,
        member = scopedMember(null),
        clientId = clientIdProvider().trim().ifBlank { null },
        playlistLimit = playlistLimit,
        genreLimit = genreLimit,
        historyLimit = historyLimit,
    )

    suspend fun streamHistory(page: Int, limit: Int): StreamHistoryResponse {
        if (page == 1) SessionDataStore.peekHistory()?.let { return it }
        return api().streamHistory(page, limit).also {
            if (page == 1) SessionDataStore.putHistory(it)
        }
    }
    suspend fun playbackStatus() = api().playbackStatus()
    suspend fun recent(page: Int, limit: Int) = api().recent(page, limit)
    suspend fun nowPlayingDevices(): NowPlayingDevicesResponse {
        val viewer = clientIdProvider().trim().ifBlank { null }
        return api().nowPlayingDevices(viewerClientId = viewer)
    }
    suspend fun rooms() = api().rooms()
    suspend fun search(
        q: String,
        limit: Int = 30,
        preview: Int = 5,
        section: String? = null,
        source: String? = null,
        fast: Boolean = section == null,
        includeResonance: Boolean = section == "similar",
        includeRooms: Boolean = false,
    ): SearchResponse {
        val response = api().search(
            q = q,
            limit = limit,
            preview = preview,
            section = section,
            source = source,
            fast = if (fast) "1" else "0",
            includeResonance = if (includeResonance) "1" else "0",
            includeRooms = if (includeRooms) "1" else "0",
        )
        val filtered = response.copy(songs = filterSearchSongHits(q, response.songs))
        filtered.artists.forEach { hit ->
            val name = hit.name ?: return@forEach
            hit.path?.let { cacheArtistArtPath(name, it) }
        }
        filtered.albums.forEach { hit ->
            val name = hit.name ?: hit.album ?: return@forEach
            hit.path?.let { cacheArtPath(name, hit.artist, it) }
        }
        return filtered
    }

    suspend fun searchSuggest(q: String): SearchResponse {
        val response = api().searchSuggest(q)
        return response.copy(songs = filterSearchSongHits(q, response.songs))
    }

    suspend fun searchPins(): List<SearchPin> = runCatching {
        api().searchPins(
            memberId = scopedMember(null),
            clientId = clientIdProvider().trim().takeIf { it.isNotBlank() },
        ).pins
    }.getOrDefault(emptyList())

    suspend fun saveSearchPins(pins: List<SearchPin>): Boolean = runCatching {
        api().saveSearchPins(
            buildJsonObject {
                scopedMember(null)?.let { put("memberId", it) }
                clientIdProvider().trim().takeIf { it.isNotBlank() }?.let { put("clientId", it) }
                putJsonArray("pins") {
                    pins.forEach { pin ->
                        add(buildJsonObject {
                            put("kind", pin.kind)
                            pin.title?.let { put("title", it) }
                            pin.name?.let { put("name", it) }
                            pin.id?.let { put("id", it) }
                            pin.artist?.let { put("artist", it) }
                            pin.path?.let { put("path", it) }
                        })
                    }
                }
            },
        )
    }.isSuccess

    suspend fun continueListening(member: String? = null): ContinueResponse =
        api().continueListening(scopedMember(member))

    /** Active household profile, unless caller passes an explicit member id. */
    private fun scopedMember(explicit: String? = null): String? =
        explicit?.trim()?.takeIf { it.isNotBlank() }
            ?: memberIdProvider()?.trim()?.takeIf { it.isNotBlank() }

    suspend fun libraryNew(since: String = "7d", followed: Boolean = false, after: String? = null): LibraryNewResponse =
        api().libraryNew(
            since = since,
            limit = if (followed) 24 else 50,
            followed = if (followed) 1 else null,
            after = after,
        )

    suspend fun followedNotifications(since: String = "30d", after: String? = null): FollowedNotificationsResponse =
        api().followedNotifications(since = since, after = after)

    suspend fun discoverWeekly(member: String? = null): DiscoverWeeklyResponse =
        api().discoverWeekly(scopedMember(member))

    suspend fun playlistFolders(): PlaylistFoldersResponse = api().playlistFolders()

    suspend fun playbackHandoff(fromDeviceId: String, toDeviceId: String, offsetMs: Int, context: JsonObject): HandoffResponse =
        api().playbackHandoff(buildJsonObject {
            put("fromDeviceId", fromDeviceId)
            put("toDeviceId", toDeviceId)
            put("offsetMs", offsetMs)
            put("context", context)
        })

    suspend fun playlists(search: String = "", page: Int = 1, limit: Int = 500, memberScoped: Boolean = false): PlaylistsResponse {
        val member = if (memberScoped) scopedMember(null) else null
        val cacheKey = member.orEmpty()
        if (search.isBlank() && page == 1) {
            SessionDataStore.peekPlaylists(cacheKey)?.let { return it }
            playlistsListCache?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < playlistsListTtlMs) return cached
            }
            return playlistsListMutex.withLock {
                SessionDataStore.peekPlaylists(cacheKey)?.let { return@withLock it }
                playlistsListCache?.let { (ts, cached) ->
                    if (System.currentTimeMillis() - ts < playlistsListTtlMs) return@withLock cached
                }
                val fresh = api().playlists(
                    page = page,
                    limit = limit,
                    search = search,
                    sortBy = "catalog",
                    member = member,
                    fields = if (search.isBlank()) "summary" else null,
                    inlineCovers = if (search.isBlank()) "0" else null,
                )
                SessionDataStore.putPlaylists(cacheKey, fresh)
                playlistsListCache = System.currentTimeMillis() to fresh
                fresh
            }
        }
        return api().playlists(
            page = page,
            limit = limit,
            search = search,
            sortBy = "catalog",
            member = member,
            fields = if (search.isBlank()) "summary" else null,
            inlineCovers = if (search.isBlank()) "0" else null,
        )
    }

    fun invalidatePlaylistsCache() {
        playlistsListCache = null
        SessionDataStore.invalidatePlaylists()
    }

    suspend fun resolvePlaylistId(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        var page = 1
        while (true) {
            val batch = runCatching {
                playlists(search = trimmed, page = page, limit = 100).items
            }.getOrNull() ?: return null
            batch.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?.id
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            if (batch.size < 100) break
            page++
        }
        return null
    }

    /** Resolve playlist id + display name for Alexa/local play. */
    suspend fun resolvePlaylistPlayArgs(target: PlayTarget.Playlist): Pair<String, String>? {
        var id = target.id.trim()
        var name = target.name.trim()
        if (id.isBlank()) id = resolvePlaylistId(name).orEmpty()
        if (name.isBlank() && id.isNotBlank()) {
            name = runCatching { playlistDetail(id, limit = 1).name.trim() }.getOrNull().orEmpty()
        }
        if (id.isBlank() && name.isNotBlank()) id = resolvePlaylistId(name).orEmpty()
        if (id.isBlank() && name.isBlank()) return null
        return id to name
    }

    suspend fun playlistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 50,
        q: String? = null,
        sortBy: String? = "original",
        order: String? = null,
    ): PlaylistDetailResponse {
        val (memberId, clientId) = ratingsScope()
        return api().playlistDetail(
            id,
            page = page,
            limit = limit,
            q = q,
            sortBy = sortBy,
            order = order,
            memberId = memberId,
            clientId = clientId,
        )
    }

    suspend fun smartPlaylists() = api().smartPlaylists()
    suspend fun artists(page: Int, search: String, limit: Int = 50) =
        api().artists(page = page, search = search, limit = limit)

    suspend fun artistDetail(artistName: String) =
        api().artistDetail(artistName.trim())

    suspend fun musicVideoRelated(artist: String, limit: Int = 12) =
        api().musicVideoRelated(artist.trim(), limit)

    suspend fun albums(page: Int, search: String, artist: String? = null, limit: Int = 50, sort: String? = null) =
        api().albums(page = page, search = search, artist = artist, limit = limit, sort = sort)

    suspend fun recentAlbums(limit: Int = 12) = albums(page = 1, search = "", limit = limit, sort = "year")

    suspend fun genres(limit: Int = 20) = api().genres(limit = limit)

    suspend fun libraryHealth() = api().libraryHealth()

    suspend fun mergeArtists(from: List<String>, to: String) =
        api().mergeArtists(buildJsonObject {
            put("to", to)
            putJsonArray("from") { from.forEach { add(JsonPrimitive(it)) } }
        })

    suspend fun resolveSearchHitArtUrl(
        kind: SearchSuggestionKind,
        hit: SearchHit,
        variantKey: String,
    ): String? {
        hit.path?.let { return artworkUrl(it) }
        return when (kind) {
            SearchSuggestionKind.Playlist -> hit.id?.let { artworkUrlForPlaylist(it, variantKey) }
            SearchSuggestionKind.Artist -> hit.name?.let { resolveArtistArtUrl(it) }
            SearchSuggestionKind.Album -> resolveAlbumArtUrl(hit.name ?: hit.album.orEmpty(), hit.artist)
            SearchSuggestionKind.Song -> null
        }
    }

    suspend fun resolveSuggestionArtUrl(suggestion: SearchSuggestion): String? {
        suggestion.path?.let { return artworkUrl(it) }
        return when (suggestion.kind) {
            SearchSuggestionKind.Playlist -> suggestion.id?.let { artworkUrlForPlaylist(it, suggestion.title) }
            SearchSuggestionKind.Artist -> resolveArtistArtUrl(suggestion.title)
            SearchSuggestionKind.Album -> resolveAlbumArtUrl(suggestion.title, suggestion.artist)
            SearchSuggestionKind.Song -> null
        }
    }

    suspend fun resolveGenreArtUrl(genre: GenreItem, sizePx: Int = ArtworkPaths.TILE_SIZE_PX): String? {
        genre.artPath?.let { return artworkUrl(it, sizePx) }
        return runCatching {
            songs(page = 1, search = genre.name, limit = 1).items.firstOrNull()?.path
        }.getOrNull()?.let { artworkUrl(it, sizePx) }
    }

    suspend fun resolveGenreArtByName(name: String, sizePx: Int = ArtworkPaths.TILE_SIZE_PX): String? {
        val label = name.trim()
        if (label.isBlank()) return null
        return runCatching {
            val items = genres(limit = 200).items
            HomeFeedRules.matchingLibraryGenreForLabel(label, items)?.let { resolveGenreArtUrl(it, sizePx) }
        }.getOrNull()
    }

    suspend fun resolveAlbumItemArtUrl(album: com.bockmedia.console.data.api.dto.AlbumItem): String? {
        album.artPath?.let { return artworkUrl(it) }
        return resolveAlbumArtUrl(album.name, album.artist)
    }

    suspend fun songs(
        page: Int,
        search: String = "",
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        limit: Int = 100,
    ) = api().songs(page = page, search = search, artist = artist, album = album, genre = genre, limit = limit)

    private val trackYearCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Release year for a file path (cached). Null when unknown or offline. */
    suspend fun trackYear(path: String): Int? {
        if (path.isBlank()) return null
        trackYearCache[path]?.let { return it }
        val year = runCatching { api().trackMeta(path).year }.getOrNull()
        if (year != null) trackYearCache[path] = year
        return year
    }

    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, LyricsResponse>()

    /** Lyrics for a track (cached per path). Returns null when unavailable or offline. */
    suspend fun lyrics(
        path: String,
        durationSec: Int? = null,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ): LyricsResponse? {
        if (path.isBlank()) return null
        val cacheKey = listOf(path, durationSec?.toString(), title, artist, album).joinToString("|")
        lyricsCache[cacheKey]?.let { return it }
        val resp = runCatching {
            api().lyrics(path, durationSec, title, artist, album)
        }.getOrNull() ?: return null
        if (resp.lines.isNotEmpty()) {
            lyricsCache[cacheKey] = resp
        }
        return resp
    }

    private val musicVideoCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val musicVideoStreamUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun musicVideoStreamKey(videoId: String, lowBandwidth: Boolean): String =
        "${videoId.trim()}|${if (lowBandwidth) "m" else "d"}"

    private fun isMusicVideoStreamUrlValid(url: String, lowBandwidth: Boolean): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        // On cellular the proxy must be reached via the external host, never a LAN IP.
        if (lowBandwidth) {
            val host = AppPreferences.hostOf(u)
            if (host != null && (
                    host.startsWith("192.168.") ||
                        host.startsWith("10.") ||
                        host == "localhost" ||
                        host == "127.0.0.1"
                    )
            ) {
                return false
            }
        }
        return true
    }

    /** Drop stale LAN stream URLs when leaving Wi‑Fi (proxy-via-external is kept). */
    fun invalidateCellularVideoStreamCache() {
        val drop = musicVideoStreamUrlCache.filter { (_, url) ->
            !isMusicVideoStreamUrlValid(url, lowBandwidth = true)
        }.keys
        drop.forEach { musicVideoStreamUrlCache.remove(it) }
    }

    /** YouTube music-video id for artist/title (cached). Null when none found. */
    suspend fun musicVideo(
        title: String,
        artist: String? = null,
        durationSec: Int? = null,
        lowBandwidth: Boolean = false,
    ): MusicVideoResponse? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val key = "${artist.orEmpty().trim().lowercase()}|${t.lowercase()}|${durationSec ?: 0}"
        musicVideoCache[key]?.let { return MusicVideoResponse(videoId = it) }
        val resp = runCatching {
            api().musicVideo(
                title = t,
                artist = artist?.trim()?.takeIf { it.isNotBlank() },
                durationSec = durationSec?.takeIf { it > 0 },
                mobile = if (lowBandwidth) "1" else null,
            )
        }.getOrNull() ?: return null
        resp.videoId?.takeIf { it.isNotBlank() }?.let { musicVideoCache[key] = it }
        return resp
    }

    suspend fun musicVideoPlay(videoId: String): MusicVideoPlayResponse? =
        musicVideoPlayWithReason(videoId)

    private suspend fun musicVideoPlayWithReason(
        videoId: String,
        lowBandwidth: Boolean = false,
        waitSec: Int? = null,
    ): MusicVideoPlayResponse? {
        val id = videoId.trim()
        if (id.isEmpty()) return null
        return try {
            api().musicVideoPlay(
                id,
                mobile = if (lowBandwidth) "1" else null,
                waitSec = waitSec,
            )
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string().orEmpty()
            runCatching { bockJson.decodeFromString<MusicVideoPlayResponse>(body) }.getOrNull()
                ?: MusicVideoPlayResponse(ready = false, reason = httpErrorMessage(e, "Video unavailable"))
        }
    }

    /** NAS proxy path — ExoPlayer plays this; avoids YouTube WebView bot-check embeds. */
    private fun musicVideoResult(
        videoId: String,
        baseUrl: String,
        playUrl: String?,
        lowBandwidth: Boolean,
        error: String? = null,
    ): MusicVideoPrepareResult {
        val resolved = playUrl?.takeIf { it.isNotBlank() }
            ?: musicVideoProxyPlayUrl(baseUrl, videoId, lowBandwidth)
        return MusicVideoPrepareResult(videoId, resolved, error?.takeIf { resolved.isNullOrBlank() })
    }

    /** One-shot lookup + stream prepare (single API round trip when wait > 0). */
    suspend fun prepareMusicVideoForTrack(
        title: String,
        artist: String? = null,
        durationSec: Int? = null,
        baseUrl: String,
        lowBandwidth: Boolean = false,
        skipStreamWait: Boolean = false,
    ): MusicVideoPrepareResult {
        val t = title.trim()
        if (t.isEmpty()) return MusicVideoPrepareResult(null, null, "Missing track title")
        val key = "${artist.orEmpty().trim().lowercase()}|${t.lowercase()}|${durationSec ?: 0}"
        val cachedId = musicVideoCache[key]
        if (cachedId != null) {
            musicVideoStreamUrlCache[musicVideoStreamKey(cachedId, lowBandwidth)]?.let { url ->
                if (isMusicVideoStreamUrlValid(url, lowBandwidth)) {
                    return MusicVideoPrepareResult(cachedId, url, null)
                }
                musicVideoStreamUrlCache.remove(musicVideoStreamKey(cachedId, lowBandwidth))
            }
            return musicVideoResult(cachedId, baseUrl, null, lowBandwidth)
        }
        val wait = if (skipStreamWait) 0 else if (lowBandwidth) 25 else 8
        val resp = runCatching {
            api().musicVideo(
                title = t,
                artist = artist?.trim()?.takeIf { it.isNotBlank() },
                durationSec = durationSec?.takeIf { it > 0 },
                mobile = if (lowBandwidth) "1" else null,
                waitSec = wait.takeIf { it > 0 },
            )
        }.getOrNull() ?: return MusicVideoPrepareResult(null, null, "Could not reach the server")
        val id = resp.videoId?.trim()?.takeIf { it.isNotBlank() }
        if (id.isNullOrBlank()) {
            return MusicVideoPrepareResult(null, null, "No music video found for this track")
        }
        musicVideoCache[key] = id
        if (resp.streamReady == true) {
            val url = resolveMusicVideoPlayUrlFromPath(baseUrl, resp.playUrl)
                ?: musicVideoProxyPlayUrl(baseUrl, id, lowBandwidth)
            if (!url.isNullOrBlank()) {
                musicVideoStreamUrlCache[musicVideoStreamKey(id, lowBandwidth)] = url
                return MusicVideoPrepareResult(id, url, null)
            }
        }
        if (skipStreamWait) {
            return musicVideoResult(id, baseUrl, null, lowBandwidth, resp.streamReason)
        }
        val (url, err) = prepareMusicVideoStream(id, baseUrl, lowBandwidth)
        return musicVideoResult(id, baseUrl, url, lowBandwidth, err)
    }

    /** Returns play URL or a user-facing error (never both). Waits for server yt-dlp on cache miss. */
    suspend fun prepareMusicVideoStream(
        videoId: String,
        baseUrl: String,
        lowBandwidth: Boolean = false,
    ): Pair<String?, String?> {
        val id = videoId.trim()
        if (id.isEmpty()) return null to "Missing video id"
        musicVideoStreamUrlCache[musicVideoStreamKey(id, lowBandwidth)]?.let { cached ->
            if (isMusicVideoStreamUrlValid(cached, lowBandwidth)) return cached to null
            musicVideoStreamUrlCache.remove(musicVideoStreamKey(id, lowBandwidth))
        }
        val wait = if (lowBandwidth) 25 else 8
        val play = musicVideoPlayWithReason(id, lowBandwidth = lowBandwidth, waitSec = wait)
            ?: return null to "Could not reach the server"
        if (!play.ready) {
            val proxy = musicVideoProxyPlayUrl(baseUrl, id, lowBandwidth)
            if (!proxy.isNullOrBlank()) return proxy to null
            return null to (play.reason?.takeIf { it.isNotBlank() } ?: "Video stream not ready on server")
        }
        val url = resolveMusicVideoPlayUrl(baseUrl, play)
            ?: musicVideoProxyPlayUrl(baseUrl, id, lowBandwidth)
        if (!url.isNullOrBlank()) {
            musicVideoStreamUrlCache[musicVideoStreamKey(id, lowBandwidth)] = url
        }
        return if (url.isNullOrBlank()) null to "Missing video stream URL" else url to null
    }

    fun resolveMusicVideoPlayUrl(base: String, resp: MusicVideoPlayResponse): String? {
        return resolveMusicVideoPlayUrlFromPath(base, resp.playUrl)
    }

    fun resolveMusicVideoPlayUrlFromPath(base: String, playUrl: String?): String? {
        val url = playUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return base.trimEnd('/') + url
    }

    /** LAN proxy path — no /play round trip; server resolves googlevideo on first byte. */
    fun musicVideoProxyPlayUrl(base: String, videoId: String, lowBandwidth: Boolean = false): String? {
        val id = videoId.trim()
        val root = base.trim().trimEnd('/')
        if (id.isEmpty() || root.isEmpty()) return null
        return if (lowBandwidth) {
            "$root/api/music-video/$id/proxy?mobile=1"
        } else {
            "$root/api/music-video/$id/proxy"
        }
    }

    suspend fun watchFolders() = api().watchFolders()
    suspend fun devices() = api().devices()
    suspend fun mergeCandidates() = api().mergeCandidates()
    suspend fun deviceGroups() = api().deviceGroups()
    suspend fun alexaRemoteDevices() = api().alexaRemoteDevices()
    suspend fun alexaRemoteStatus(probe: Boolean = false) =
        api().alexaRemoteStatus(probe = if (probe) "1" else null)
    suspend fun automations() = api().automations()
    suspend fun analytics(
        from: String? = null,
        to: String? = null,
        deviceId: String? = null,
        member: String? = null,
        platform: String? = null,
        householdWide: Boolean = false,
    ): AnalyticsResponse {
        if (from == null && to == null && deviceId == null && platform == null) {
            SessionDataStore.peekAnalytics()?.let { return it }
        }
        return api().analytics(
            from,
            to,
            deviceId,
            if (householdWide) null else scopedMember(member),
            platform,
        ).also {
            if (from == null && to == null && deviceId == null && platform == null) {
                SessionDataStore.putAnalytics(it)
            }
        }
    }

    // ── Household / Family ──────────────────────────────────────────────────
    suspend fun household() = api().household()

    suspend fun createMember(name: String, role: String) =
        api().createMember(buildJsonObject {
            put("name", name)
            put("role", role)
        })

    suspend fun updateMember(id: String, role: String) =
        api().updateMember(id, buildJsonObject { put("role", role) })

    suspend fun deleteMember(id: String) = api().deleteMember(id)

    suspend fun setMemberPin(id: String, pin: String, currentPin: String?) =
        api().setMemberPin(id, buildJsonObject {
            put("pin", pin)
            if (!currentPin.isNullOrBlank()) put("currentPin", currentPin)
        })

    suspend fun setDeviceOwner(deviceId: String, memberId: String?) =
        if (memberId.isNullOrBlank()) {
            api().clearDeviceOwner(deviceId)
        } else {
            api().setDeviceOwner(deviceId, buildJsonObject { put("memberId", memberId) })
        }

    suspend fun bindClient(clientId: String, memberId: String?, phoneId: String? = null) {
        api().bindClient(buildJsonObject {
            put("clientId", clientId)
            if (!memberId.isNullOrBlank()) put("memberId", memberId)
            if (!phoneId.isNullOrBlank()) put("phoneId", phoneId)
        })
    }

    suspend fun connectInstall(phoneId: String, deviceName: String, clientId: String): String? =
        api().reportClientEvent(buildJsonObject {
            put("clientId", clientId)
            put("platform", "android")
            put("deviceName", deviceName)
            put("event", "connect")
            if (phoneId.isNotBlank()) put("phoneId", phoneId)
        }).memberId?.takeIf { it.isNotBlank() }

    suspend fun clientPrefs(clientId: String, memberId: String?) =
        api().clientPrefs(clientId, memberId?.takeIf { it.isNotBlank() })

    suspend fun putClientPrefs(
        clientId: String,
        memberId: String?,
        memberPrefs: JsonObject,
        clientPrefs: JsonObject,
    ) = api().putClientPrefs(buildJsonObject {
        put("clientId", clientId)
        if (!memberId.isNullOrBlank()) put("memberId", memberId)
        if (memberPrefs.isNotEmpty()) put("memberPrefs", memberPrefs)
        if (clientPrefs.isNotEmpty()) put("clientPrefs", clientPrefs)
    })

    suspend fun roomPolicy(deviceId: String) = api().roomPolicy(deviceId)

    suspend fun setRoomPolicy(deviceId: String, body: JsonObject) =
        api().setRoomPolicy(deviceId, body)

    suspend fun householdAnalytics() = api().householdAnalytics()

    suspend fun messages(member: String?) = api().messages(member)

    suspend fun sendMessage(fromMemberId: String?, toMemberId: String?, text: String) =
        api().sendMessage(buildJsonObject {
            put("text", text)
            put("clientId", clientIdProvider())
            put("scope", if (!toMemberId.isNullOrBlank()) "direct" else "household")
            if (!fromMemberId.isNullOrBlank()) put("fromMemberId", fromMemberId)
            if (!toMemberId.isNullOrBlank()) put("toMemberId", toMemberId)
        })

    suspend fun roomRequest(
        deviceId: String,
        path: String,
        track: String? = null,
        artist: String? = null,
    ): RoomRequestItem {
        val (memberId, clientId) = ratingsScope()
        return api().roomRequest(deviceId, buildJsonObject {
            put("path", path)
            track?.let { put("track", it) }
            artist?.let { put("artist", it) }
            memberId?.let { put("memberId", it) }
            clientId?.let { put("clientId", it) }
        })
    }

    suspend fun roomQueue(deviceId: String) = api().roomQueue(deviceId)

    suspend fun approveRoomRequest(deviceId: String, requestId: String, pin: String): RoomRequestItem {
        val (memberId, _) = ratingsScope()
        return api().approveRoomRequest(deviceId, requestId, buildJsonObject {
            put("pin", pin)
            memberId?.let { put("memberId", it) }
        })
    }

    suspend fun deleteRoomRequest(deviceId: String, requestId: String) {
        api().deleteRoomRequest(deviceId, requestId)
    }

    suspend fun reorderRoomRequests(deviceId: String, order: List<String>): RoomQueueResponse {
        return api().reorderRoomRequests(deviceId, buildJsonObject {
            putJsonArray("order") {
                order.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
        })
    }

    fun clientDeviceId(): String {
        val cid = clientIdProvider().trim().lowercase()
        return if (cid.isBlank()) "" else "client-$cid"
    }
    suspend fun reportClientEvent(body: JsonObject) = api().reportClientEvent(body)
    suspend fun ignored() = api().ignored()
    suspend fun settings() = api().settings()
    suspend fun config() = api().config()
    suspend fun localIp() = api().localIp()
    suspend fun identifyStatus() = api().identifyStatus()

    suspend fun artworkUrl(filepath: String?, sizePx: Int? = null): String? {
        if (filepath.isNullOrBlank()) return null
        val base = runCatching { baseUrl() }.getOrNull() ?: return null
        return AppPreferences.artworkUrl(base, filepath, sizePx)
    }

    suspend fun resolvePlaybackArtUrl(
        context: Context,
        libraryPath: String?,
        localFile: File? = null,
    ): String? {
        libraryPath?.let { artworkUrl(it) }?.let { return it }
        localFile?.let { PlaybackArtwork.embeddedArtUri(context.applicationContext, it) }?.let { return it }
        return null
    }

    suspend fun playOnDevice(
        device: String,
        kind: String,
        name: String? = null,
        id: String? = null,
        path: String? = null,
        artist: String? = null,
        shuffle: Boolean = false,
    ): PlayResponse {
        val (memberId, clientId) = ratingsScope()
        val body = buildJsonObject {
            put("device", device)
            put("kind", kind)
            name?.let { put("name", it) }
            id?.let { put("id", it) }
            path?.let { put("path", it) }
            artist?.let { put("artist", it) }
            put("shuffle", shuffle)
            memberId?.let { put("memberId", it) }
            clientId?.let { put("clientId", it) }
        }
        return api().playOnDevice(body)
    }

    suspend fun deviceControl(deviceId: String, deviceName: String, serial: String?, action: String): PlayResponse {
        val body = buildJsonObject {
            put("deviceId", deviceId)
            put("device", deviceName)
            serial?.let { put("serial", it) }
            put("action", action)
        }
        return api().alexaRemoteControl(body)
    }

    suspend fun seekQueueIndex(
        deviceId: String,
        deviceName: String,
        serial: String?,
        relativeIndex: Int,
    ): PlayResponse {
        val body = buildJsonObject {
            put("deviceId", deviceId)
            put("device", deviceName)
            serial?.let { put("serial", it) }
            put("action", "seek_queue_index")
            put("index", relativeIndex)
            put("relative", true)
        }
        return api().alexaRemoteControl(body)
    }

    suspend fun setVolume(serial: String, deviceName: String, volume: Int) {
        api().setVolume(buildJsonObject {
            put("serial", serial)
            put("device", deviceName)
            put("volume", volume)
        })
    }

    suspend fun getVolume(serial: String) = api().getVolume(serial)

    suspend fun setSleep(deviceId: String, minutes: Int? = null, songs: Int? = null) {
        api().nowPlayingSleep(buildJsonObject {
            put("deviceId", deviceId)
            minutes?.let { put("minutes", it) }
            songs?.let { put("songs", it) }
        })
    }

    suspend fun ratedSongs(): List<RatingItem> {
        val (memberId, clientId) = ratingsScope()
        return api().ratings(memberId = memberId, clientId = clientId).items
            .filter { it.kind == RatingKind.Song.apiValue && it.stars > 0 }
    }

    suspend fun ratingCountForMember(memberId: String): Int =
        api().ratings(
            memberId = memberId.takeIf { it.isNotBlank() },
            clientId = clientIdProvider().trim().takeIf { it.isNotBlank() },
        ).items.count { it.kind == RatingKind.Song.apiValue && it.stars > 0 }

    suspend fun ratedSongMap(): Map<String, Int> {
        val cached = ratedSongMapCache
        if (cached != null && System.currentTimeMillis() - cached.first < ratedSongMapTtlMs) {
            return cached.second
        }
        val map = ratedSongs().associate { it.id to it.stars }
        ratedSongMapCache = System.currentTimeMillis() to map
        return map
    }

    fun clearRatingsCache() {
        ratingsCache.clear()
        ratedSongMapCache = null
    }

    private fun ratingsScope(): Pair<String?, String?> {
        val memberId = memberIdProvider()?.trim()?.takeIf { it.isNotBlank() }
        val clientId = clientIdProvider().trim().takeIf { it.isNotBlank() }
        return memberId to clientId
    }

    private fun ratingCacheKey(kind: RatingKind, id: String) = "${kind.apiValue}:${id.trim()}"

    suspend fun ratingStars(kind: RatingKind, id: String): Int {
        val key = ratingCacheKey(kind, id)
        if (key.endsWith(":")) return 0
        ratingsCache[key]?.let { return it }
        val (memberId, clientId) = ratingsScope()
        val stars = runCatching {
            api().ratingLookup(kind.apiValue, id, memberId = memberId, clientId = clientId).stars
        }.getOrDefault(0)
        ratingsCache[key] = stars
        return stars
    }

    suspend fun setRating(
        kind: RatingKind,
        id: String,
        stars: Int,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ) {
        val (memberId, clientId) = ratingsScope()
        api().setRating(buildJsonObject {
            put("kind", kind.apiValue)
            put("id", id)
            put("stars", stars.coerceIn(0, 5))
            title?.let { put("title", it) }
            artist?.let { put("artist", it) }
            album?.let { put("album", it) }
            memberId?.let { put("memberId", it) }
            clientId?.let { put("clientId", it) }
        })
        ratingsCache[ratingCacheKey(kind, id)] = stars.coerceIn(0, 5)
    }

    suspend fun streamUrl(filepath: String?): String? {
        val base = runCatching { baseUrlProvider() }.getOrNull() ?: return null
        return AppPreferences.streamUrl(base, filepath)
    }

    suspend fun addIgnored(path: String) {
        api().addIgnored(buildJsonObject { put("path", path) })
    }

    suspend fun removeIgnored(path: String) {
        api().removeIgnored(buildJsonObject { put("path", path) })
    }

    suspend fun createPlaylist(name: String, tracks: List<String> = emptyList()) {
        api().createPlaylist(buildJsonObject {
            put("name", name)
            if (tracks.isNotEmpty()) {
                putJsonArray("tracks") { tracks.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            }
        })
    }

    suspend fun renamePlaylist(id: String, name: String) {
        api().renamePlaylist(buildJsonObject { put("id", id); put("name", name) })
    }

    suspend fun sharePlaylist(id: String, toMemberIds: List<String>) {
        val (memberId, clientId) = ratingsScope()
        api().sharePlaylist(id, buildJsonObject {
            putJsonArray("toMemberIds") {
                toMemberIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            memberId?.let { put("memberId", it) }
            clientId?.let { put("clientId", it) }
        })
        invalidatePlaylistsCache()
    }

    /** Keep a daily playlist forever — stops the daily regenerator from overwriting it. */
    suspend fun saveDailyPlaylist(id: String, name: String? = null) {
        api().saveDailyPlaylist(id, buildJsonObject { name?.let { put("name", it) } })
    }

    suspend fun deletePlaylist(id: String) = api().deletePlaylist(id)
    suspend fun mergePlaylists(ids: List<String>, name: String?) {
        api().mergePlaylists(buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            name?.let { put("name", it) }
        })
    }

    suspend fun removePlaylistTrack(id: String, path: String) {
        api().removePlaylistTrack(id, buildJsonObject { put("path", path) })
    }

    suspend fun sortPlaylist(id: String, sortBy: String, order: String) {
        api().sortPlaylist(id, buildJsonObject { put("by", sortBy); put("order", order) })
    }

    suspend fun movePlaylistTrack(playlistId: String, path: String, toIndex: Int) {
        api().movePlaylistTrack(playlistId, buildJsonObject {
            put("path", path)
            put("toIndex", toIndex)
        })
    }

    suspend fun continuationTracks(
        mode: String,
        target: PlayTarget?,
        lastPath: String,
        lastArtist: String?,
        exclude: Set<String>,
    ): List<LocalTrack> {
        if (mode == "off" || mode.isBlank()) return emptyList()
        val resp = when {
            mode == "artist_radio" -> {
                val artist = lastArtist?.trim().orEmpty()
                if (artist.isBlank()) return emptyList()
                resonanceRadio(seedKind = "artist", artist = artist)
            }
            target is PlayTarget.Playlist -> resonanceRadio(seedKind = "playlist", playlistId = target.id)
            target is PlayTarget.Album -> resonanceRadio(seedKind = "album", album = target.name, artist = target.artist)
            target is PlayTarget.Artist -> resonanceRadio(seedKind = "artist", artist = target.name)
            else -> resonanceRadio(seedKind = "song", path = lastPath)
        }
        return resp.tracks.mapNotNull { t ->
            val path = t.path?.takeIf { it.isNotBlank() && it !in exclude } ?: return@mapNotNull null
            LocalTrack(path = path, title = t.title ?: path, artist = t.artist, album = t.album,
                durationMs = t.duration?.takeIf { it > 0 }?.times(1000L) ?: 0L)
        }
    }

    suspend fun aiPlaylist(prompt: String, name: String, maxTracks: Int, save: Boolean) =
        api().aiPlaylist(buildJsonObject {
            put("prompt", prompt)
            put("name", name)
            put("maxTracks", maxTracks)
            put("save", save)
        })

    suspend fun listenAgentStatus() = api().listenAgentStatus()

    suspend fun listenAgentPlay(prompt: String) = api().listenAgentPlay(buildJsonObject {
        put("prompt", prompt)
    })

    suspend fun mixMuseStatus() = api().mixMuseStatus()

    suspend fun mixMusePlaylist(
        prompt: String,
        name: String = "",
        maxTracks: Int = 25,
        save: Boolean = true,
    ) = api().mixMusePlaylist(buildJsonObject {
        put("prompt", prompt)
        if (name.isNotBlank()) put("name", name)
        put("maxTracks", maxTracks)
        put("save", save)
    })

    suspend fun mixMuseSimilar(
        seedKind: String,
        path: String? = null,
        album: String? = null,
        artist: String? = null,
        playlistId: String? = null,
        prompt: String,
        save: Boolean = true,
        maxTracks: Int = 25,
    ) = api().mixMuseSimilar(buildJsonObject {
        put("seedKind", seedKind)
        path?.let { put("path", it) }
        album?.let { put("album", it) }
        artist?.let { put("artist", it) }
        playlistId?.let { put("playlistId", it) }
        put("prompt", prompt)
        put("save", save)
        put("maxTracks", maxTracks)
    })

    suspend fun resonanceRadio(
        seedKind: String,
        path: String? = null,
        album: String? = null,
        artist: String? = null,
        playlistId: String? = null,
        maxTracks: Int = 30,
    ) = api().resonanceRadio(buildJsonObject {
        put("seedKind", seedKind)
        path?.let { put("path", it) }
        album?.let { put("album", it) }
        artist?.let { put("artist", it) }
        playlistId?.let { put("playlistId", it) }
        put("maxTracks", maxTracks)
    })

    suspend fun resonanceMix(
        seedKind: String,
        path: String? = null,
        album: String? = null,
        artist: String? = null,
        playlistId: String? = null,
        maxTracks: Int = 30,
        save: Boolean = true,
    ) = api().resonanceMix(buildJsonObject {
        put("seedKind", seedKind)
        path?.let { put("path", it) }
        album?.let { put("album", it) }
        artist?.let { put("artist", it) }
        playlistId?.let { put("playlistId", it) }
        put("maxTracks", maxTracks)
        put("save", save)
    })

    suspend fun acquireSuggest(
        seedKind: String,
        path: String? = null,
        album: String? = null,
        artist: String? = null,
        playlistId: String? = null,
        limit: Int = 24,
    ) = api().acquireSuggest(buildJsonObject {
        put("seedKind", seedKind)
        path?.let { put("path", it) }
        album?.let { put("album", it) }
        artist?.let { put("artist", it) }
        playlistId?.let { put("playlistId", it) }
        put("limit", limit)
    })

    suspend fun acquireExplore(limit: Int = 24) = api().acquireExplore(limit)

    suspend fun createSmartPlaylist(name: String, genre: String?, artist: String?, maxTracks: Int) {
        val rules = buildJsonArray {
            genre?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("type", "genre"); put("value", it) })
            }
            artist?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("type", "artist"); put("value", it) })
            }
            add(buildJsonObject { put("type", "limit"); put("value", maxTracks) })
        }
        api().createSmartPlaylist(buildJsonObject {
            put("name", name)
            put("rules", rules)
            put("refresh", true)
        })
    }

    suspend fun updateSmartPlaylist(
        id: String,
        name: String,
        genre: String?,
        artist: String?,
        maxTracks: Int,
        enabled: Boolean,
    ) {
        val rules = buildJsonArray {
            genre?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("type", "genre"); put("value", it) })
            }
            artist?.takeIf { it.isNotBlank() }?.let {
                add(buildJsonObject { put("type", "artist"); put("value", it) })
            }
            add(buildJsonObject { put("type", "limit"); put("value", maxTracks) })
        }
        api().updateSmartPlaylist(id, buildJsonObject {
            put("name", name)
            put("rules", rules)
            put("enabled", enabled)
            put("refresh", true)
        })
    }

    suspend fun addPlaylistTrack(playlistId: String, path: String) {
        val tracks = mutableListOf<String>()
        var page = 1
        while (true) {
            val detail = playlistDetail(playlistId, page = page, limit = 500, sortBy = "original")
            tracks.addAll(detail.tracks.mapNotNull { it.path })
            if (detail.tracks.size < 500 || tracks.size >= detail.total) break
            page++
        }
        if (path in tracks) return
        tracks.add(path)
        api().updatePlaylist(playlistId, buildJsonObject {
            putJsonArray("tracks") { tracks.forEach { add(JsonPrimitive(it)) } }
        })
    }

    suspend fun exportAnalyticsCsv(
        from: String?,
        to: String?,
        cacheDir: java.io.File,
        deviceId: String? = null,
        member: String? = null,
        householdWide: Boolean = false,
    ): File {
        val body = api().analyticsExport(
            from,
            to,
            deviceId,
            if (householdWide) null else scopedMember(member),
        )
        val file = java.io.File(cacheDir, "bock_media_streams.csv")
        body.byteStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    suspend fun loadSettingsJson(): JsonObject = api().settings()
    suspend fun loadConfigJson(): JsonObject = api().config()

    suspend fun refreshSmartPlaylist(id: String) = api().refreshSmartPlaylist(id)
    suspend fun deleteSmartPlaylist(id: String) = api().deleteSmartPlaylist(id)

    suspend fun renameDevice(id: String, name: String) {
        api().renameDevice(id, buildJsonObject { put("name", name) })
    }

    suspend fun deleteDevice(id: String) = api().deleteDevice(id)
    suspend fun mergeDevices(sourceId: String, targetId: String) {
        api().mergeDevice(sourceId, buildJsonObject { put("target", targetId) })
    }

    suspend fun dismissMergeCandidate(id: String) = api().dismissMergeCandidate(id)
    suspend fun identifyDevices() = api().identifyDevices()
    suspend fun testDevice(serial: String, name: String? = null) = api().testDevice(buildJsonObject {
        put("serial", serial)
        name?.let { put("name", it) }
    })

    suspend fun createDeviceGroup(name: String, devices: List<String>) {
        api().createDeviceGroup(buildJsonObject {
            put("name", name)
            putJsonArray("devices") { devices.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        })
    }

    suspend fun updateDeviceGroup(id: String, name: String, devices: List<String>) {
        api().updateDeviceGroup(id, buildJsonObject {
            put("name", name)
            putJsonArray("devices") { devices.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        })
    }

    suspend fun deleteDeviceGroup(id: String) = api().deleteDeviceGroup(id)

    suspend fun createAutomation(body: JsonObject) = api().createAutomation(body)
    suspend fun updateAutomation(id: String, body: JsonObject) = api().updateAutomation(id, body)
    suspend fun deleteAutomation(id: String) = api().deleteAutomation(id)
    suspend fun runAutomation(id: String) = api().runAutomation(id)

    suspend fun saveSettings(body: JsonObject) = api().saveSettings(body)
    suspend fun saveConfig(body: JsonObject) = api().saveConfig(body)
    suspend fun clearCache() = api().clearCache()

    suspend fun connectionSummary(): String = runCatching {
        val active = baseUrl()
        val local = preferences.getLocalServerUrlSync()?.let { AppPreferences.normalizeUrl(it) }
        when {
            local != null && active.startsWith(local) -> "LAN · $local"
            else -> "External · $active"
        }
    }.getOrDefault("Unknown")

    suspend fun alexaLoginStart(): AlexaRemoteStatus {
        val existing = runCatching { api().alexaLoginState() }.getOrNull()
        val status = existing?.loginStatus?.takeIf { it.isNotBlank() }
            ?: existing?.status?.takeIf { it.isNotBlank() }
        if (status == "waiting" || status == "starting") {
            runCatching { api().alexaLoginStop() }
        }
        val body = buildJsonObject {
            val status = runCatching { api().alexaRemoteStatus() }.getOrNull()
            val lanFromServer = status?.loginProxyHost?.takeIf { it.isNotBlank() }
                ?: status?.host?.takeIf { it.isNotBlank() }
            val local = preferences.getLocalServerUrlSync()?.let { AppPreferences.normalizeUrl(it) }
            val active = runCatching { baseUrl() }.getOrNull()
            val host = lanFromServer
                ?: when {
                    local != null && active != null && active.startsWith(local) ->
                        AppPreferences.hostOf(local)
                    else -> local?.let { AppPreferences.hostOf(it) }
                }
            host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
        }
        return api().alexaLoginStart(body)
    }
    suspend fun alexaLoginStop() = api().alexaLoginStop()
    suspend fun alexaLoginState() = api().alexaLoginState()
}
