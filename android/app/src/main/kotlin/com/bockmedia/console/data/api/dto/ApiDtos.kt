package com.bockmedia.console.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class OkResponse(val ok: Boolean = false, val error: String? = null, val code: String? = null)

@Serializable
data class SummaryResponse(
    val songs: Int = 0,
    val artists: Int = 0,
    val albums: Int = 0,
    val playlists: Int = 0,
    val watchFolders: Int = 0,
)

@Serializable
data class HealthResponse(
    val uptimeSeconds: Int? = null,
    val lastAlexaHit: Double? = null,
    val lastAlexaHitAgo: Int? = null,
    val watchdogFresh: Boolean? = null,
    val watchdogAgeSeconds: Int? = null,
    val backend: Boolean? = null,
    val tunnel: Boolean? = null,
    @SerialName("backendHttp") val backendHttp: Boolean? = null,
    @SerialName("tunnelReachable") val tunnelReachable: Boolean? = null,
    val publicLatencyMs: Int? = null,
    val publicStatus: Int? = null,
    val alexaAuth: Boolean? = null,
    val skillTesting: JsonElement? = null,
    val plexConfigured: Boolean? = null,
    val plexReachable: Boolean? = null,
)

@Serializable
data class PlaylistCoverResponse(
    @SerialName("playlistId") val playlistId: String? = null,
    val path: String? = null,
)

@Serializable
data class PlaylistCoversBatchRequest(val ids: List<String> = emptyList())

@Serializable
data class PlaylistCoversBatchResponse(
    val covers: Map<String, String> = emptyMap(),
    val collages: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class RecentResponse(val items: List<RecentPlayRequestItem> = emptyList(), val total: Int = 0)

@Serializable
data class RecentPlayRequestItem(
    val heard: String? = null,
    val found: String? = null,
    val success: Boolean = false,
    val timestamp: String? = null,
)

@Serializable
data class RecentItem(
    val track: String? = null,
    val artist: String? = null,
    val playlist: String? = null,
    val device: String? = null,
    val timestamp: String? = null,
    val path: String? = null,
)

@Serializable
data class DashboardQuickResponse(
    val recent: List<RecentItem> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
)

@Serializable
data class FavoritesResponse(val items: List<FavoriteItem> = emptyList())

@Serializable
data class RatingItem(
    val kind: String = "",
    val id: String = "",
    val stars: Int = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
data class RatingsResponse(val items: List<RatingItem> = emptyList())

@Serializable
data class RatingLookupResponse(
    val kind: String = "",
    val id: String = "",
    val stars: Int = 0,
)

@Serializable
data class FavoriteItem(
    val path: String = "",
    @SerialName("title") val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val count: Int = 0,
)

@Serializable
data class PlexSyncStatusResponse(
    val lastRun: String? = null,
    val lastSuccess: String? = null,
    val playlistCount: Int? = null,
    val logTail: List<String> = emptyList(),
    val running: Boolean = false,
)

@Serializable
data class PlaybackStatusResponse(
    val alexaRemote: AlexaRemoteStatus? = null,
)

@Serializable
data class AlexaRemoteStatus(
    val configured: Boolean = false,
    val authenticated: Boolean? = null,
    val available: Boolean = false,
    val deviceCount: Int? = null,
    @SerialName("loginStatus") val loginStatus: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("loginUrl") val loginUrl: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("loginError") val loginError: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("loginProxyHost") val loginProxyHost: String? = null,
    @SerialName("host") val host: String? = null,
    @SerialName("loginProxyPort") val loginProxyPort: Int? = null,
    @SerialName("port") val port: Int? = null,
    @SerialName("portReady") val portReady: Boolean? = null,
)

@Serializable
data class NowPlayingDevicesResponse(
    val items: List<NowPlayingDeviceItem> = emptyList(),
    val controlsAvailable: Boolean = false,
)

@Serializable
data class NowPlayingDeviceItem(
    val deviceId: String = "",
    val deviceName: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val filepath: String? = null,
    val timestamp: Double? = null,
    val duration_ms: Long = 0,
    val offset_ms: Long = 0,
    val paused: Boolean = false,
    val playlist: String? = null,
    val playlistId: String? = null,
    val sourceLabel: String? = null,
    val sleep: SleepInfo? = null,
    val upcoming: List<UpcomingTrack> = emptyList(),
)

@Serializable
data class SleepInfo(val type: String? = null, val remainingMin: Int? = null, val remaining: Int? = null)

@Serializable
data class UpcomingTrack(
    val title: String? = null,
    val artist: String? = null,
    val path: String? = null,
)

@Serializable
data class StreamHistoryResponse(
    val items: List<StreamHistoryItem> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class StreamHistoryItem(
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val device: String? = null,
    val date: String? = null,
    val timestamp: String? = null,
    val playlist: String? = null,
    val sourceLabel: String? = null,
    val filepath: String? = null,
)

@Serializable
data class RoomsResponse(val rooms: List<RoomItem> = emptyList())

@Serializable
data class RoomNowPlaying(
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val paused: Boolean = false,
    val sourceLabel: String? = null,
    val playlist: String? = null,
)

@Serializable
data class RoomItem(
    val name: String? = null,
    val serial: String? = null,
    val deviceId: String? = null,
    val pseudo: Boolean = false,
    val nowPlaying: RoomNowPlaying? = null,
    val automations: List<AutomationPreview> = emptyList(),
)

@Serializable
data class AutomationPreview(val id: String? = null, val name: String? = null, val time: String? = null, val label: String? = null)

@Serializable
data class SearchResponse(
    val query: String? = null,
    val playlists: List<SearchHit> = emptyList(),
    val artists: List<SearchHit> = emptyList(),
    val albums: List<SearchHit> = emptyList(),
    val songs: List<SearchHit> = emptyList(),
    val genres: List<SearchHit> = emptyList(),
    val smartPlaylists: List<SearchHit> = emptyList(),
    val rooms: List<SearchHit> = emptyList(),
    val messages: List<SearchHit> = emptyList(),
    val radios: List<SearchRadioHit> = emptyList(),
    val similar: List<SearchHit> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val preview: Int = 5,
)

@Serializable
data class SearchRadioHit(
    val kind: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val displayTitle: String? = null,
    val path: String? = null,
)

@Serializable
data class SearchPinsResponse(val pins: List<SearchPin> = emptyList())

@Serializable
data class SearchPin(
    val kind: String = "",
    val title: String? = null,
    val name: String? = null,
    val id: String? = null,
    val artist: String? = null,
    val path: String? = null,
)

@Serializable
data class SearchHit(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val path: String? = null,
    val tracks: Int? = null,
    val albums: Int? = null,
)

@Serializable
data class PlaylistsResponse(
    val items: List<PlaylistSummary> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class PlaylistSummary(
    val id: String = "",
    val name: String = "",
    @SerialName("trackCount") val tracks: Int = 0,
    val source: String? = null,
    val sourceName: String? = null,
    val createDate: String? = null,
    val lastUsed: String? = null,
    val folderId: String? = null,
    val artPath: String? = null,
)

@Serializable
data class PlaylistDetailResponse(
    val id: String = "",
    val name: String = "",
    val tracks: List<PlaylistTrack> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val source: String? = null,
    val sourceName: String? = null,
    val daily: Boolean = false,
    val dailyRecipe: String? = null,
)

@Serializable
data class PlaylistTrack(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val path: String? = null,
    val duration: Int? = null,
)

@Serializable
data class SmartPlaylistsResponse(val items: List<SmartPlaylist> = emptyList())

@Serializable
data class SmartPlaylist(
    val id: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("linkedPlaylistId") val playlistId: String? = null,
    val trackCount: Int? = null,
    val lastRefresh: String? = null,
    val rules: JsonObject? = null,
)

@Serializable
data class ArtistsResponse(val items: List<ArtistItem> = emptyList(), val total: Int = 0)

@Serializable
data class ArtistPortraitResponse(
    val artist: String = "",
    @SerialName("art_path") val artPath: String? = null,
    val source: String? = null,
    val cached: Boolean = false,
)

@Serializable
data class ArtistItem(
    @SerialName("artist") val name: String = "",
    @SerialName("track_count") val tracks: Int = 0,
    @SerialName("album_count") val albums: Int = 0,
    @SerialName("art_path") val artPath: String? = null,
)

@Serializable
data class AlbumsResponse(val items: List<AlbumItem> = emptyList(), val total: Int = 0)

@Serializable
data class AlbumItem(
    @SerialName("album") val name: String = "",
    val artist: String? = null,
    @SerialName("track_count") val tracks: Int = 0,
    val year: Int? = null,
    @SerialName("art_path") val artPath: String? = null,
    val played: Boolean = true,
) {
    val unplayed: Boolean get() = !played
}

@Serializable
data class GenresResponse(val items: List<GenreItem> = emptyList(), val total: Int = 0)

@Serializable
data class GenreItem(
    val name: String = "",
    @SerialName("track_count") val tracks: Int = 0,
    @SerialName("art_path") val artPath: String? = null,
)

@Serializable
data class SongsResponse(val items: List<SongItem> = emptyList(), val total: Int = 0)

@Serializable
data class TrackMetaResponse(
    val path: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: Int? = null,
)

@Serializable
data class LyricsResponse(
    val synced: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    val plain: String = "",
    val source: String? = null,
)

@Serializable
data class LyricLine(
    @SerialName("timeMs") val timeMs: Long = 0,
    val text: String = "",
)

@Serializable
data class SongItem(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val path: String? = null,
    @SerialName("duration_seconds") val duration: Int? = null,
    val genre: String? = null,
    val year: Int? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
)

@Serializable
data class WatchFoldersResponse(val items: List<WatchFolder> = emptyList())

@Serializable
data class WatchFolder(
    val guid: String? = null,
    val path: String? = null,
    val label: String? = null,
    val status: String? = null,
    val count: Int = 0,
    @SerialName("identifiedFiles") val identifiedFiles: Int = 0,
    val errors: Int = 0,
    val playlists: Int = 0,
    val type: String? = null,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceItem> = emptyList())

@Serializable
data class DeviceItem(
    val deviceId: String = "",
    val name: String? = null,
    val lastSeen: Double? = null,
    val firstSeen: Double? = null,
)

@Serializable
data class MergeCandidate(
    val sourceId: String = "",
    val targetId: String = "",
    val sourceName: String? = null,
    val targetName: String? = null,
    val score: Double? = null,
)

@Serializable
data class MergeCandidatesResponse(val candidates: List<MergeCandidate> = emptyList())

@Serializable
data class DeviceGroupsResponse(val items: List<DeviceGroup> = emptyList())

@Serializable
data class DeviceGroup(
    val id: String = "",
    val name: String = "",
    val members: List<GroupMember> = emptyList(),
)

@Serializable
data class GroupMember(
    val serial: String = "",
    val name: String? = null,
)

@Serializable
data class AlexaDevicesResponse(val devices: List<AlexaDevice> = emptyList())

@Serializable
data class AlexaDevice(
    val name: String? = null,
    val serial: String? = null,
    val online: Boolean = false,
    val family: String? = null,
)

@Serializable
data class IdentifyStatusResponse(
    val running: Boolean = false,
    val current: String? = null,
    val done: Int = 0,
    val total: Int = 0,
)

@Serializable
data class AutomationsResponse(val items: List<AutomationItem> = emptyList())

@Serializable
data class AutomationItem(
    val id: String = "",
    val name: String = "",
    val label: String = "",
    val playlist: String? = null,
    val playlistId: String? = null,
    val playlistName: String? = null,
    val device: String? = null,
    val deviceName: String? = null,
    val time: String? = null,
    val volume: Int? = null,
    val shuffle: Boolean = false,
    val enabled: Boolean = true,
    val repeat: String? = null,
    val days: List<Int> = emptyList(),
)

@Serializable
data class AnalyticsResponse(
    @SerialName("totalPlays") val totalPlays: Int = 0,
    @SerialName("uniqueTracks") val uniqueTracks: Int = 0,
    @SerialName("uniqueArtists") val uniqueArtists: Int = 0,
    @SerialName("uniqueAlbums") val uniqueAlbums: Int = 0,
    val activity: AnalyticsActivity? = null,
    @SerialName("hourOfDay") val hourOfDay: List<HourCount> = emptyList(),
    @SerialName("dayOfWeek") val dayOfWeek: List<DayCount> = emptyList(),
    val heatmap: List<List<Int>>? = null,
    val topArtists: List<CountRow> = emptyList(),
    val topAlbums: List<CountRow> = emptyList(),
    val topTracks: List<CountRow> = emptyList(),
    val topDevices: List<CountRow> = emptyList(),
    val topGenres: List<CountRow> = emptyList(),
    @SerialName("topDecades") val topDecades: List<DecadeRow> = emptyList(),
    @SerialName("listeningStreak") val listeningStreak: ListeningStreak? = null,
    @SerialName("currentStreak") val currentStreak: Int = 0,
    @SerialName("longestStreak") val longestStreak: Int = 0,
    @SerialName("catalogCoverage") val catalogCoverage: CatalogCoverage? = null,
    @SerialName("repeatRate") val repeatRate: RepeatRate? = null,
    @SerialName("mostActiveDay") val mostActiveDay: MostActiveDay? = null,
    @SerialName("deviceBreakdown") val deviceBreakdown: List<DeviceBreakdownRow> = emptyList(),
)

@Serializable
data class AnalyticsActivity(
    val day: List<ActivityPoint> = emptyList(),
    val week: List<ActivityPoint> = emptyList(),
    val month: List<ActivityPoint> = emptyList(),
    val year: List<ActivityPoint> = emptyList(),
)

@Serializable
data class ActivityPoint(val label: String = "", val count: Int = 0)

@Serializable
data class HourCount(val hour: Int = 0, val count: Int = 0)

@Serializable
data class DayCount(val day: String = "", val count: Int = 0)

@Serializable
data class DecadeRow(val decade: String? = null, val count: Int = 0)

@Serializable
data class ListeningStreak(val current: Int = 0, val longest: Int = 0)

@Serializable
data class CatalogCoverage(val heard: Int = 0, val total: Int = 0, val pct: Double = 0.0)

@Serializable
data class RepeatRate(val repeated: Int = 0, val total: Int = 0, val pct: Double = 0.0)

@Serializable
data class MostActiveDay(val date: String = "", val count: Int = 0)

@Serializable
data class DeviceBreakdownRow(
    @SerialName("deviceId") val deviceId: String = "",
    val name: String = "",
    val platform: String = "",
    val plays: Int = 0,
    val downloads: Int = 0,
    val connects: Int = 0,
)

@Serializable
data class CountRow(
    val label: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val count: Int = 0,
)

fun CountRow.displayName(): String = label ?: name ?: "—"

@Serializable
data class IgnoredResponse(val items: List<IgnoredTrack> = emptyList())

@Serializable
data class IgnoredTrack(
    val path: String = "",
    @SerialName("title") val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
data class SettingsResponse(val settings: JsonObject? = null)

@Serializable
data class ConfigResponse(val config: JsonObject? = null)

@Serializable
data class LocalIpResponse(val ip: String? = null)

@Serializable
data class VolumeResponse(val volume: Int? = null)

@Serializable
data class PlayResponse(
    val device: String? = null,
    val text: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
    val code: String? = null,
)

@Serializable
data class AiPlaylistResponse(
    val preview: List<PlaylistTrack> = emptyList(),
    val name: String? = null,
    val id: String? = null,
    val tracks: List<PlaylistTrack> = emptyList(),
    val trackCount: Int? = null,
    val playlistId: String? = null,
    val source: String? = null,
    val prompt: String? = null,
)

@Serializable
data class MixMuseStatusResponse(
    val configured: Boolean = false,
    val provider: String? = null,
    val supportsOpenAi: Boolean = false,
    val supportsClaude: Boolean = false,
)

@Serializable
data class DiscoveryMixResponse(
    val name: String? = null,
    val tracks: List<PlaylistTrack> = emptyList(),
    val trackCount: Int? = null,
    val playlistId: String? = null,
    val id: String? = null,
    val source: String? = null,
    val shuffle: Boolean? = null,
    val seed: DiscoverySeedInfo? = null,
)

@Serializable
data class DiscoverySeedInfo(
    val path: String? = null,
    val title: String? = null,
    val artist: String? = null,
)

@Serializable
data class AcquireSuggestion(
    val name: String = "",
    val mbid: String? = null,
    val reasons: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val inLibrary: Boolean = false,
    val musicbrainzUrl: String? = null,
)

@Serializable
data class AcquireSuggestResponse(
    val source: String? = null,
    val seed: AcquireSeedInfo? = null,
    val suggestions: List<AcquireSuggestion> = emptyList(),
    val note: String? = null,
    val error: String? = null,
)

@Serializable
data class AcquireSeedInfo(
    val kind: String? = null,
    val artist: String? = null,
    val resolvedName: String? = null,
    val mbid: String? = null,
    val artists: List<String>? = null,
)

@Serializable
data class ContinueResponse(val resume: ResumeEntry? = null, val recent: List<ResumeEntry> = emptyList())

@Serializable
data class ResumeEntry(
    val id: String? = null,
    val filepath: String? = null,
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val offsetMs: Int = 0,
    val durationMs: Int = 0,
    val progress: Double = 0.0,
    val context: ResumeContext? = null,
)

@Serializable
data class ResumeContext(val kind: String? = null, val id: String? = null, val name: String? = null)

@Serializable
data class LibraryNewResponse(
    val since: String? = null,
    val albums: List<LibraryNewAlbum> = emptyList(),
    val tracks: List<LibraryNewTrack> = emptyList(),
)

@Serializable
data class LibraryNewAlbum(
    val album: String? = null,
    val artist: String? = null,
    val path: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String? = null,
)

@Serializable
data class LibraryNewTrack(val title: String? = null, val artist: String? = null, val album: String? = null, val path: String? = null)

@Serializable
data class DiscoverWeeklyResponse(
    val memberId: String? = null,
    val generatedAt: String? = null,
    val sections: List<DiscoverSection> = emptyList(),
)

@Serializable
data class DiscoverSection(val id: String? = null, val title: String? = null, val reason: String? = null, val tracks: List<DiscoverTrack> = emptyList())

@Serializable
data class DiscoverTrack(val path: String? = null, val title: String? = null, val artist: String? = null, val album: String? = null)

@Serializable
data class PlaylistFoldersResponse(val folders: List<PlaylistFolder> = emptyList(), val assignments: Map<String, String> = emptyMap())

@Serializable
data class PlaylistFolder(val id: String = "", val name: String = "", val parentId: String? = null, val order: Int = 0)

@Serializable
data class HandoffResponse(
    val ok: Boolean = false,
    val method: String? = null,
    val filepath: String? = null,
    val offsetMs: Int? = null,
    val streamUrl: String? = null,
    val warning: String? = null,
    val error: String? = null,
)
