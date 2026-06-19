package com.bockmedia.console.data.api

import com.bockmedia.console.data.api.dto.*
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.http.*

interface BockMediaApi {
    @GET("api/summary")
    suspend fun summary(): SummaryResponse

    @GET("api/health")
    suspend fun health(): HealthResponse

    @GET("api/plex_sync/status")
    suspend fun plexSyncStatus(): PlexSyncStatusResponse

    @GET("api/dashboard/quick")
    suspend fun dashboardQuick(): DashboardQuickResponse

    @GET("api/playback/status")
    suspend fun playbackStatus(): PlaybackStatusResponse

    @GET("api/recent")
    suspend fun recent(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20,
    ): RecentResponse

    @GET("api/nowplaying_devices")
    suspend fun nowPlayingDevices(
        @Query("viewerClientId") viewerClientId: String? = null,
    ): NowPlayingDevicesResponse

    @GET("api/nowplaying")
    suspend fun streamHistory(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 25,
    ): StreamHistoryResponse

    @POST("api/nowplaying/sleep")
    suspend fun nowPlayingSleep(@Body body: JsonObject): OkResponse

    @GET("api/rooms")
    suspend fun rooms(): RoomsResponse

    @GET("api/search")
    suspend fun search(@Query("q") q: String, @Query("limit") limit: Int = 30): SearchResponse

    @GET("api/playlists")
    suspend fun playlists(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100,
        @Query("search") search: String = "",
        @Query("sortBy") sortBy: String? = null,
        @Query("order") order: String? = null,
    ): PlaylistsResponse

    @GET("api/playlists/{id}/cover")
    suspend fun playlistCover(@Path("id") id: String): PlaylistCoverResponse

    @POST("api/playlists/covers")
    suspend fun playlistCoversBatch(@Body body: PlaylistCoversBatchRequest): PlaylistCoversBatchResponse

    @GET("api/playlists/{id}")
    suspend fun playlistDetail(
        @Path("id") id: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100,
        @Query("sortBy") sortBy: String? = null,
        @Query("order") order: String? = null,
        @Query("q") q: String? = null,
    ): PlaylistDetailResponse

    @POST("api/playlists")
    suspend fun createPlaylist(@Body body: JsonObject): OkResponse

    @PUT("api/playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @DELETE("api/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): OkResponse

    @POST("api/playlists/rename")
    suspend fun renamePlaylist(@Body body: JsonObject): OkResponse

    @POST("api/playlists/merge")
    suspend fun mergePlaylists(@Body body: JsonObject): OkResponse

    @POST("api/playlists/{id}/sort")
    suspend fun sortPlaylist(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @POST("api/playlists/{id}/tracks/remove")
    suspend fun removePlaylistTrack(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @POST("api/playlists/play")
    suspend fun playOnDevice(@Body body: JsonObject): PlayResponse

    @POST("api/playlists/ai")
    suspend fun aiPlaylist(@Body body: JsonObject): AiPlaylistResponse

    @GET("api/smart_playlists")
    suspend fun smartPlaylists(): SmartPlaylistsResponse

    @POST("api/smart_playlists")
    suspend fun createSmartPlaylist(@Body body: JsonObject): OkResponse

    @PUT("api/smart_playlists/{id}")
    suspend fun updateSmartPlaylist(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @DELETE("api/smart_playlists/{id}")
    suspend fun deleteSmartPlaylist(@Path("id") id: String): OkResponse

    @POST("api/smart_playlists/{id}/refresh")
    suspend fun refreshSmartPlaylist(@Path("id") id: String): OkResponse

    @GET("api/artists")
    suspend fun artists(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("search") search: String = "",
    ): ArtistsResponse

    @GET("api/albums")
    suspend fun albums(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("search") search: String = "",
        @Query("artist") artist: String? = null,
        @Query("sort") sort: String? = null,
    ): AlbumsResponse

    @GET("api/genres")
    suspend fun genres(@Query("limit") limit: Int = 20): GenresResponse

    @GET("api/track_meta")
    suspend fun trackMeta(@Query("path") path: String): TrackMetaResponse

    @GET("api/songs")
    suspend fun songs(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100,
        @Query("search") search: String = "",
        @Query("artist") artist: String? = null,
        @Query("album") album: String? = null,
    ): SongsResponse

    @GET("api/watchfolders")
    suspend fun watchFolders(): List<WatchFolder>

    @GET("api/devices")
    suspend fun devices(): List<DeviceItem>

    @POST("api/devices/{id}")
    suspend fun renameDevice(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @DELETE("api/devices/{id}")
    suspend fun deleteDevice(@Path("id") id: String): OkResponse

    @POST("api/devices/{sourceId}/merge")
    suspend fun mergeDevice(@Path("sourceId") sourceId: String, @Body body: JsonObject): OkResponse

    @GET("api/devices/merge_candidates")
    suspend fun mergeCandidates(): MergeCandidatesResponse

    @POST("api/devices/{id}/dismiss_candidate")
    suspend fun dismissMergeCandidate(@Path("id") id: String): OkResponse

    @POST("api/devices/identify")
    suspend fun identifyDevices(): OkResponse

    @GET("api/devices/identify/status")
    suspend fun identifyStatus(): IdentifyStatusResponse

    @POST("api/devices/test")
    suspend fun testDevice(@Body body: JsonObject): OkResponse

    @GET("api/device_groups")
    suspend fun deviceGroups(): DeviceGroupsResponse

    @POST("api/device_groups")
    suspend fun createDeviceGroup(@Body body: JsonObject): OkResponse

    @PUT("api/device_groups/{id}")
    suspend fun updateDeviceGroup(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @DELETE("api/device_groups/{id}")
    suspend fun deleteDeviceGroup(@Path("id") id: String): OkResponse

    @GET("api/automations")
    suspend fun automations(): AutomationsResponse

    @POST("api/automations")
    suspend fun createAutomation(@Body body: JsonObject): OkResponse

    @PUT("api/automations/{id}")
    suspend fun updateAutomation(@Path("id") id: String, @Body body: JsonObject): OkResponse

    @DELETE("api/automations/{id}")
    suspend fun deleteAutomation(@Path("id") id: String): OkResponse

    @POST("api/automations/{id}/run")
    suspend fun runAutomation(@Path("id") id: String): OkResponse

    @POST("api/clients/report")
    suspend fun reportClientEvent(@Body body: JsonObject): OkResponse

    @GET("api/analytics")
    suspend fun analytics(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): AnalyticsResponse

    @GET("api/analytics/export")
    @Streaming
    suspend fun analyticsExport(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): ResponseBody

    @GET("api/ignored")
    suspend fun ignored(): IgnoredResponse

    @POST("api/ignored")
    suspend fun addIgnored(@Body body: JsonObject): OkResponse

    @HTTP(method = "DELETE", path = "api/ignored", hasBody = true)
    suspend fun removeIgnored(@Body body: JsonObject): OkResponse

    @GET("api/favorites")
    suspend fun favorites(): FavoritesResponse

    @POST("api/favorites")
    suspend fun addFavorite(@Body body: JsonObject): OkResponse

    @HTTP(method = "DELETE", path = "api/favorites", hasBody = true)
    suspend fun removeFavorite(@Body body: JsonObject): OkResponse

    @GET("api/settings")
    suspend fun settings(): JsonObject

    @POST("api/settings")
    suspend fun saveSettings(@Body body: JsonObject): OkResponse

    @GET("api/config")
    suspend fun config(): JsonObject

    @POST("api/config")
    suspend fun saveConfig(@Body body: JsonObject): OkResponse

    @GET("api/localip")
    suspend fun localIp(): LocalIpResponse

    @POST("api/clearcache")
    suspend fun clearCache(): OkResponse

    @GET("api/alexa_remote/status")
    suspend fun alexaRemoteStatus(@Query("probe") probe: String? = null): AlexaRemoteStatus

    @GET("api/alexa_remote/devices")
    suspend fun alexaRemoteDevices(): AlexaDevicesResponse

    @POST("api/alexa_remote/play")
    suspend fun alexaRemotePlay(@Body body: JsonObject): PlayResponse

    @POST("api/alexa_remote/control")
    suspend fun alexaRemoteControl(@Body body: JsonObject): PlayResponse

    @GET("api/alexa_remote/volume")
    suspend fun getVolume(@Query("serial") serial: String): VolumeResponse

    @POST("api/alexa_remote/volume")
    suspend fun setVolume(@Body body: JsonObject): OkResponse

    @GET("api/alexa_remote/login")
    suspend fun alexaLoginState(): AlexaRemoteStatus

    @POST("api/alexa_remote/login/start")
    suspend fun alexaLoginStart(@Body body: JsonObject = JsonObject(emptyMap())): AlexaRemoteStatus

    @POST("api/alexa_remote/login/stop")
    suspend fun alexaLoginStop(): AlexaRemoteStatus
}
