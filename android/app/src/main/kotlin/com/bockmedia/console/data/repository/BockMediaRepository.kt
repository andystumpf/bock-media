package com.bockmedia.console.data.repository

import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.dto.*
import com.bockmedia.console.data.local.AppPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class ApiException(val code: String, message: String) : Exception(message)

class BockMediaRepository(
    private val apiProvider: suspend () -> BockMediaApi,
    private val baseUrlProvider: suspend () -> String,
    private val preferences: AppPreferences,
) {
    private suspend fun api() = apiProvider()

    suspend fun testConnection(): Result<HealthResponse> = runCatching { api().health() }

    suspend fun summary() = api().summary()
    suspend fun health() = api().health()
    suspend fun plexSyncStatus() = api().plexSyncStatus()
    suspend fun dashboardQuick() = api().dashboardQuick()
    suspend fun playbackStatus() = api().playbackStatus()
    suspend fun recent(page: Int, limit: Int) = api().recent(page, limit)
    suspend fun nowPlayingDevices() = api().nowPlayingDevices()
    suspend fun streamHistory(page: Int, limit: Int) = api().streamHistory(page, limit)
    suspend fun rooms() = api().rooms()
    suspend fun search(q: String) = api().search(q)
    suspend fun playlists(search: String = "", page: Int = 1, limit: Int = 500) =
        api().playlists(page = page, limit = limit, search = search)

    suspend fun playlistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 50,
        q: String? = null,
        sortBy: String? = null,
        order: String? = null,
    ) = api().playlistDetail(id, page = page, limit = limit, q = q, sortBy = sortBy, order = order)

    suspend fun smartPlaylists() = api().smartPlaylists()
    suspend fun artists(page: Int, search: String) = api().artists(page = page, search = search)
    suspend fun albums(page: Int, search: String, artist: String? = null) =
        api().albums(page = page, search = search, artist = artist)

    suspend fun songs(page: Int, search: String, artist: String? = null, album: String? = null) =
        api().songs(page = page, search = search, artist = artist, album = album)

    suspend fun watchFolders() = api().watchFolders()
    suspend fun devices() = api().devices()
    suspend fun mergeCandidates() = api().mergeCandidates()
    suspend fun deviceGroups() = api().deviceGroups()
    suspend fun alexaRemoteDevices() = api().alexaRemoteDevices()
    suspend fun alexaRemoteStatus() = api().alexaRemoteStatus()
    suspend fun automations() = api().automations()
    suspend fun analytics(from: String? = null, to: String? = null) = api().analytics(from, to)
    suspend fun ignored() = api().ignored()
    suspend fun settings() = api().settings()
    suspend fun config() = api().config()
    suspend fun localIp() = api().localIp()
    suspend fun identifyStatus() = api().identifyStatus()

    suspend fun artworkUrl(filepath: String?): String? {
        val base = runCatching { baseUrlProvider() }.getOrNull() ?: return null
        return AppPreferences.artworkUrl(base, filepath)
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
        api().createSmartPlaylist(buildJsonObject {
            put("name", name)
            genre?.let { put("genre", it) }
            artist?.let { put("artist", it) }
            put("maxTracks", maxTracks)
        })
    }

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
    suspend fun alexaLoginStart() = api().alexaLoginStart()
    suspend fun alexaLoginStop() = api().alexaLoginStop()
    suspend fun alexaLoginState() = api().alexaLoginState()
}
