package com.bockmedia.console.data.repository

import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.ArtworkPaths
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

    private suspend fun baseUrl(): String =
        cachedBaseUrl ?: baseUrlProvider().also { cachedBaseUrl = it }

    /** Best-effort base URL for sync artwork URL building (no network). */
    fun peekBaseUrl(): String? {
        val cached = cachedBaseUrl
        val local = BuildConfig.DEFAULT_LOCAL_SERVER_URL.takeIf { it.isNotBlank() }
        val external = BuildConfig.DEFAULT_EXTERNAL_SERVER_URL.takeIf { it.isNotBlank() }
        if (cached != null && AppPreferences.isLanHost(cached, local, external) && !NetworkReachability.onWifi) {
            return external ?: cached
        }
        return cached ?: external ?: local
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

    fun clearCaches() {
        cachedBaseUrl = null
        baseUrlEpoch++
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
    suspend fun dashboardQuick() = api().dashboardQuick()
    suspend fun playbackStatus() = api().playbackStatus()
    suspend fun recent(page: Int, limit: Int) = api().recent(page, limit)
    suspend fun nowPlayingDevices(): NowPlayingDevicesResponse {
        val viewer = clientIdProvider().trim().ifBlank { null }
        return api().nowPlayingDevices(viewerClientId = viewer)
    }
    suspend fun streamHistory(page: Int, limit: Int) = api().streamHistory(page, limit)
    suspend fun rooms() = api().rooms()
    suspend fun search(
        q: String,
        limit: Int = 30,
        preview: Int = 5,
        section: String? = null,
        source: String? = null,
    ): SearchResponse {
        val response = api().search(q, limit = limit, preview = preview, section = section, source = source)
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

    suspend fun searchSuggest(q: String): SearchResponse = api().searchSuggest(q)

    suspend fun continueListening(member: String? = null): ContinueResponse =
        api().continueListening(scopedMember(member))

    /** Active household profile, unless caller passes an explicit member id. */
    private fun scopedMember(explicit: String? = null): String? =
        explicit?.trim()?.takeIf { it.isNotBlank() }
            ?: memberIdProvider()?.trim()?.takeIf { it.isNotBlank() }

    suspend fun libraryNew(since: String = "7d"): LibraryNewResponse = api().libraryNew(since = since)

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

    suspend fun playlists(search: String = "", page: Int = 1, limit: Int = 500): PlaylistsResponse {
        // Only the default full listing (no search, first page) is shared — that's the
        // payload Home/Library/Search all request. Searches/paging always hit the API.
        val shareable = search.isBlank() && page == 1
        if (shareable) {
            playlistsListCache?.let { (ts, cached) ->
                if (System.currentTimeMillis() - ts < playlistsListTtlMs) return cached
            }
            return playlistsListMutex.withLock {
                playlistsListCache?.let { (ts, cached) ->
                    if (System.currentTimeMillis() - ts < playlistsListTtlMs) return@withLock cached
                }
                val fresh = api().playlists(page = page, limit = limit, search = search)
                playlistsListCache = System.currentTimeMillis() to fresh
                fresh
            }
        }
        return api().playlists(page = page, limit = limit, search = search)
    }

    fun invalidatePlaylistsCache() {
        playlistsListCache = null
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
        sortBy: String? = null,
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

    suspend fun songs(page: Int, search: String, artist: String? = null, album: String? = null, limit: Int = 100) =
        api().songs(page = page, search = search, artist = artist, album = album, limit = limit)

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
    ) = api().analytics(from, to, deviceId, scopedMember(member), platform)

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

    suspend fun ratedSongMap(): Map<String, Int> =
        ratedSongs().associate { it.id to it.stars }

    fun clearRatingsCache() {
        ratingsCache.clear()
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
            val detail = playlistDetail(playlistId, page = page, limit = 500)
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
    ): File {
        val body = api().analyticsExport(from, to, deviceId, scopedMember(member))
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
            val active = runCatching { baseUrl() }.getOrNull()
            val local = preferences.getLocalServerUrlSync()?.let { AppPreferences.normalizeUrl(it) }
            val host = when {
                local != null && active != null && active.startsWith(local) ->
                    AppPreferences.hostOf(local)
                else ->
                    active?.let { AppPreferences.hostOf(it) }
            }
            host?.takeIf { it.isNotBlank() }?.let { put("host", it) }
        }
        return api().alexaLoginStart(body)
    }
    suspend fun alexaLoginStop() = api().alexaLoginStop()
    suspend fun alexaLoginState() = api().alexaLoginState()
}
