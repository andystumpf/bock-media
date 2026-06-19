package com.bockmedia.console.data.repository

import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.SearchSuggestion
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
) {
    private suspend fun api() = apiProvider()

    private val playlistTrackPathsCache = ConcurrentHashMap<String, List<String>>()
    private val artistCoverPathsCache = ConcurrentHashMap<String, List<String>>()
    private val albumCoverPathCache = ConcurrentHashMap<String, String>()
    @Volatile private var cachedBaseUrl: String? = null

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
        url?.takeIf { it.isNotBlank() }?.let { cachedBaseUrl = AppPreferences.normalizeUrl(it) }
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
            .filter { HomeArtworkCache.playlistPath(it) == null && playlistTrackPathsCache[it].isNullOrEmpty() }
        if (missing.isEmpty()) return
        val covers = runCatching {
            api().playlistCoversBatch(PlaylistCoversBatchRequest(missing)).covers
        }.getOrDefault(emptyMap())
        if (covers.isNotEmpty()) {
            HomeArtworkCache.storePlaylistPaths(covers)
            covers.forEach { (id, path) -> playlistTrackPathsCache[id] = listOf(path) }
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

    suspend fun resolveArtistArtUrl(artistName: String, sizePx: Int? = null): String? =
        artistCoverPathAt(artistName)?.let { artworkUrl(it, sizePx) }

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
    ): String? {
        val pick = kotlin.math.abs(cardId.hashCode())
        playlistId?.let { return artworkUrlForPlaylist(it, it) }
        artPath?.let { return artworkUrl(it) }
        return when (playTarget) {
            is PlayTarget.Album -> {
                runCatching {
                    val paths = songs(page = 1, search = playTarget.name, artist = playTarget.artist, album = playTarget.name)
                        .items.mapNotNull { it.path?.takeIf { path -> path.isNotBlank() } }
                        .distinct()
                    paths.getOrNull(pick % paths.size.coerceAtLeast(1))
                }.getOrNull()?.let { artworkUrl(it) }
            }
            is PlayTarget.Artist -> artistCoverPathAt(playTarget.name, pick)?.let { artworkUrl(it) }
            is PlayTarget.Radio -> {
                playTarget.path?.let { return artworkUrl(it) }
                artistCoverPathAt(playTarget.name, pick)?.let { artworkUrl(it) }
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
        playlistTrackPathsCache.clear()
        artistCoverPathsCache.clear()
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
    suspend fun search(q: String, limit: Int = 30): SearchResponse {
        val response = api().search(q, limit = limit)
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
        return runCatching {
            playlists(search = trimmed, limit = 50).items
                .firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?.id
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun playlistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 50,
        q: String? = null,
        sortBy: String? = null,
        order: String? = null,
    ) = api().playlistDetail(id, page = page, limit = limit, q = q, sortBy = sortBy, order = order)

    suspend fun smartPlaylists() = api().smartPlaylists()
    suspend fun artists(page: Int, search: String, limit: Int = 50) =
        api().artists(page = page, search = search, limit = limit)
    suspend fun albums(page: Int, search: String, artist: String? = null, limit: Int = 50, sort: String? = null) =
        api().albums(page = page, search = search, artist = artist, limit = limit, sort = sort)

    suspend fun recentAlbums(limit: Int = 12) = albums(page = 1, search = "", limit = limit, sort = "year")

    suspend fun genres(limit: Int = 20) = api().genres(limit = limit)

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

    suspend fun resolveGenreArtUrl(genre: com.bockmedia.console.data.api.dto.GenreItem): String? {
        genre.artPath?.let { return artworkUrl(it) }
        return runCatching {
            songs(page = 1, search = genre.name, limit = 1).items.firstOrNull()?.path
        }.getOrNull()?.let { artworkUrl(it) }
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

    suspend fun watchFolders() = api().watchFolders()
    suspend fun devices() = api().devices()
    suspend fun mergeCandidates() = api().mergeCandidates()
    suspend fun deviceGroups() = api().deviceGroups()
    suspend fun alexaRemoteDevices() = api().alexaRemoteDevices()
    suspend fun alexaRemoteStatus(probe: Boolean = false) =
        api().alexaRemoteStatus(probe = if (probe) "1" else null)
    suspend fun automations() = api().automations()
    suspend fun analytics(from: String? = null, to: String? = null) = api().analytics(from, to)
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
        val body = buildJsonObject {
            put("device", device)
            put("kind", kind)
            name?.let { put("name", it) }
            id?.let { put("id", it) }
            path?.let { put("path", it) }
            artist?.let { put("artist", it) }
            put("shuffle", shuffle)
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

    suspend fun addFavorite(path: String, track: String?, artist: String?, album: String?) {
        api().addFavorite(buildJsonObject {
            put("path", path)
            track?.let { put("title", it) }
            artist?.let { put("artist", it) }
            album?.let { put("album", it) }
        })
    }

    suspend fun removeFavorite(path: String) {
        api().removeFavorite(buildJsonObject { put("path", path) })
    }

    suspend fun favorites(): List<FavoriteItem> = api().favorites().items

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
        api().sortPlaylist(id, buildJsonObject { put("sortBy", sortBy); put("order", order) })
    }

    suspend fun aiPlaylist(prompt: String, name: String, maxTracks: Int, save: Boolean) =
        api().aiPlaylist(buildJsonObject {
            put("prompt", prompt)
            put("name", name)
            put("maxTracks", maxTracks)
            put("save", save)
        })

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

    suspend fun exportAnalyticsCsv(from: String?, to: String?, cacheDir: java.io.File): File {
        val body = api().analyticsExport(from, to)
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
