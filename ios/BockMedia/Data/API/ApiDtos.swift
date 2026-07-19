import Foundation

// MARK: - Core

struct OkResponse: Codable {
    var ok: Bool = false
    var error: String?
    var code: String?
    var memberId: String?
    var deviceId: String?
}

struct ClientPrefsResponse: Codable {
    var v: Int = 1
    var memberId: String?
    var clientDeviceId: String?
    var ok: Bool = false
    var merged: [String: AnyCodable] = [:]
    var memberPrefs: [String: AnyCodable] = [:]

    var mergedDict: [String: Any] {
        merged.mapValues { $0.value }
    }

    var memberPrefsDict: [String: Any] {
        memberPrefs.mapValues { $0.value }
    }
}

/// Lightweight JSON value for client prefs merge payloads.
struct AnyCodable: Codable {
    let value: Any

    init(_ value: Any) { self.value = value }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let b = try? container.decode(Bool.self) { value = b; return }
        if let i = try? container.decode(Int.self) { value = i; return }
        if let d = try? container.decode(Double.self) { value = d; return }
        if let s = try? container.decode(String.self) { value = s; return }
        if let arr = try? container.decode([AnyCodable].self) { value = arr.map(\.value); return }
        if let dict = try? container.decode([String: AnyCodable].self) {
            value = dict.mapValues { $0.value }
            return
        }
        value = NSNull()
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch value {
        case let b as Bool: try container.encode(b)
        case let i as Int: try container.encode(i)
        case let d as Double: try container.encode(d)
        case let s as String: try container.encode(s)
        case let arr as [Any]: try container.encode(arr.map { AnyCodable($0) })
        case let dict as [String: Any]: try container.encode(dict.mapValues { AnyCodable($0) })
        default: try container.encodeNil()
        }
    }
}

struct SummaryResponse: Codable {
    var songs: Int = 0
    var artists: Int = 0
    var albums: Int = 0
    var playlists: Int = 0
    var watchFolders: Int = 0
}

struct SecurityWarning: Codable {
    var id: String?
    var severity: String?
    var message: String?
    var action: String?
}

struct MusicVideoHealth: Codable {
    var cookiesStale: Bool?
    var cookiesPresent: Bool?
    var verifiedOk: Bool?
    var refreshHint: String?
}

struct HealthResponse: Codable {
    var uptimeSeconds: Int?
    var lastAlexaHit: Double?
    var lastAlexaHitAgo: Int?
    var watchdogFresh: Bool?
    var watchdogAgeSeconds: Int?
    var backend: Bool?
    var tunnel: Bool?
    var backendHttp: Bool?
    var tunnelReachable: Bool?
    var publicLatencyMs: Int?
    var publicStatus: Int?
    var alexaAuth: Bool?
    var plexConfigured: Bool?
    var plexReachable: Bool?
    var credentialsConfigured: Bool?
    var allowOpenLanApi: Bool?
    var allowOpenLanMedia: Bool?
    var insecureConfig: Bool?
    var securityWarnings: [SecurityWarning]?
    var musicVideo: MusicVideoHealth?
}

struct LibraryHealthResponse: Codable {
    var summary: LibraryHealthSummary = LibraryHealthSummary()
    var attentionFolders: [LibraryAttentionFolder] = []
    var duplicateArtists: [DuplicateArtistGroup] = []
}

struct LibraryHealthSummary: Codable {
    var totalTracks: Int = 0
    var missingGenre: Int = 0
    var missingAlbumArtist: Int = 0
    var needsAttention: Int = 0
}

struct LibraryAttentionFolder: Codable {
    var path: String = ""
    var trackCount: Int = 0
}

struct DuplicateArtistGroup: Codable {
    var canonical: String = ""
    var variants: [String] = []
    var totalTracks: Int = 0
}

struct DashboardQuickResponse: Codable {
    var recent: [RecentItem] = []
    var favorites: [FavoriteItem] = []
}

struct RecentItem: Codable, Identifiable {
    var track: String?
    var artist: String?
    var playlist: String?
    var device: String?
    var timestamp: String?
    var path: String?

    var id: String { path ?? "\(track ?? "")-\(timestamp ?? UUID().uuidString)" }
}

struct FavoritesResponse: Codable {
    var items: [FavoriteItem] = []
}

struct RatingsResponse: Codable {
    var items: [RatingItem] = []
}

struct RatingLookupResponse: Codable {
    var stars: Int = 0
}

struct RatingItem: Codable, Identifiable {
    var kind: String = "song"
    var id: String = ""
    var stars: Int = 0
    var title: String?
    var artist: String?
    var album: String?
}

struct FavoriteItem: Codable, Identifiable {
    var path: String = ""
    var title: String?
    var artist: String?
    var album: String?
    var count: Int = 0

    var id: String { path }
}

struct PlaybackStatusResponse: Codable {
    var alexaRemote: AlexaRemoteStatus?
}

struct AlexaRemoteStatus: Codable {
    var configured: Bool = false
    var authenticated: Bool?
    var available: Bool = false
    var deviceCount: Int?
    var loginStatus: String?
    var status: String?
    var loginUrl: String?
    var url: String?
    var loginError: String?
    var error: String?
    var loginProxyHost: String?
    var host: String?
    var loginProxyPort: Int?
    var port: Int?
    var portReady: Bool?

    var effectiveLoginStatus: String? {
        let s = loginStatus?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let s, !s.isEmpty { return s }
        let t = status?.trimmingCharacters(in: .whitespacesAndNewlines)
        return t?.isEmpty == false ? t : nil
    }

    var effectiveLoginURL: URL? {
        if let loginUrl, let url = URL(string: loginUrl) { return url }
        if let url, let parsed = URL(string: url) { return parsed }
        let h = loginProxyHost?.trimmingCharacters(in: .whitespacesAndNewlines)
            ?? host?.trimmingCharacters(in: .whitespacesAndNewlines)
        let p = loginProxyPort ?? port
        if let h, !h.isEmpty, let p {
            return URL(string: "http://\(h):\(p)")
        }
        return nil
    }
}

struct NowPlayingDevicesResponse: Codable {
    var items: [NowPlayingDeviceItem] = []
    var controlsAvailable: Bool = false
}

struct NowPlayingDeviceItem: Codable, Identifiable {
    var deviceId: String = ""
    var deviceName: String?
    var track: String?
    var artist: String?
    var album: String?
    var filepath: String?
    var timestamp: Double?
    var duration_ms: Int64 = 0
    var offset_ms: Int64 = 0
    var paused: Bool = false
    var shuffle: Bool = false
    var loop: Bool = false
    var playlist: String?
    var playlistId: String?
    var sourceLabel: String?
    var sleep: SleepInfo?
    var upcoming: [UpcomingTrack] = []
    var upNext: [RoomRequestItem] = []

    var id: String { deviceId }
}

struct RoomRequestItem: Codable, Identifiable {
    var id: String = ""
    var path: String?
    var track: String?
    var artist: String?
    var byMemberId: String?
    var byMemberName: String?
    var status: String?
    var ts: Double?
}

struct RoomQueueResponse: Codable {
    var deviceId: String = ""
    var deviceName: String?
    var queue: [RoomRequestItem] = []
}

struct SleepInfo: Codable {
    var type: String?
    var remainingMin: Int?
    var remaining: Int?
}

struct UpcomingTrack: Codable, Identifiable {
    var title: String?
    var artist: String?
    var path: String?

    var id: String { path ?? "\(title ?? "")-\(artist ?? "")" }
}

struct StreamHistoryResponse: Codable {
    var items: [StreamHistoryItem] = []
    var total: Int = 0
}

struct StreamHistoryItem: Codable {
    var track: String?
    var artist: String?
    var album: String?
    var device: String?
    var date: String?
    var timestamp: String?
    var playlist: String?
    var sourceLabel: String?
    var filepath: String?
}

struct SearchResponse: Codable {
    var query: String?
    var playlists: [SearchHit] = []
    var artists: [SearchHit] = []
    var albums: [SearchHit] = []
    var songs: [SearchHit] = []
    var genres: [SearchHit] = []
    var smartPlaylists: [SearchHit] = []
    var rooms: [SearchHit] = []
    var messages: [SearchHit] = []
    var radios: [SearchRadioHit] = []
    var similar: [SearchHit] = []
    var counts: [String: Int] = [:]
    var preview: Int = 5

    var hasAnyMatches: Bool {
        !songs.isEmpty || !artists.isEmpty || !albums.isEmpty || !playlists.isEmpty ||
        !genres.isEmpty || !smartPlaylists.isEmpty
    }
}

struct SearchRadioHit: Codable, Identifiable {
    var kind: String?
    var name: String?
    var artist: String?
    var displayTitle: String?
    var path: String?

    var id: String { path ?? name ?? displayTitle ?? UUID().uuidString }
}

struct SearchPinsResponse: Codable {
    var pins: [SearchPin] = []
}

struct SearchPin: Codable, Identifiable {
    var kind: String = ""
    var title: String?
    var name: String?
    var id: String?
    var artist: String?
    var path: String?

    var displayId: String { id ?? path ?? name ?? title ?? UUID().uuidString }
}

struct SearchHit: Codable, Identifiable {
    var id: String?
    var name: String?
    var title: String?
    var artist: String?
    var album: String?
    var genre: String?
    var path: String?
    var tracks: Int?
    var albums: Int?

    var displayId: String { id ?? path ?? name ?? title ?? UUID().uuidString }
    
    /// Primary label for artists/albums/playlists in search UI.
    var displayName: String? {
        let n = name?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let n, !n.isEmpty { return n }
        let t = title?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let t, !t.isEmpty { return t }
        let a = album?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let a, !a.isEmpty { return a }
        return nil
    }
}

struct PlaylistsResponse: Codable {
    var items: [PlaylistSummary] = []
    var total: Int = 0
}

struct PlaylistSummary: Codable, Identifiable {
    var id: String = ""
    var name: String = ""
    var trackCount: Int = 0
    var source: String?
    var sourceName: String?
    var createDate: String?
    var lastUsed: String?
    var folderId: String?
    var artPath: String?
    var ownerMemberId: String?
    var ownerName: String?
    var visibility: String?
    var sharedWith: [String] = []
    var daily: Bool = false

    var tracks: Int { trackCount }

    enum CodingKeys: String, CodingKey {
        case id, name, trackCount, tracks, source, sourceName, createDate, lastUsed
        case folderId, artPath, ownerMemberId, ownerName, visibility, sharedWith, daily
    }

    init(
        id: String = "",
        name: String = "",
        trackCount: Int = 0,
        source: String? = nil,
        sourceName: String? = nil,
        createDate: String? = nil,
        lastUsed: String? = nil,
        folderId: String? = nil,
        artPath: String? = nil,
        ownerMemberId: String? = nil,
        ownerName: String? = nil,
        visibility: String? = nil,
        sharedWith: [String] = [],
        daily: Bool = false
    ) {
        self.id = id
        self.name = name
        self.trackCount = trackCount
        self.source = source
        self.sourceName = sourceName
        self.createDate = createDate
        self.lastUsed = lastUsed
        self.folderId = folderId
        self.artPath = artPath
        self.ownerMemberId = ownerMemberId
        self.ownerName = ownerName
        self.visibility = visibility
        self.sharedWith = sharedWith
        self.daily = daily
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        trackCount = try c.decodeIfPresent(Int.self, forKey: .trackCount)
            ?? c.decodeIfPresent(Int.self, forKey: .tracks) ?? 0
        source = try c.decodeIfPresent(String.self, forKey: .source)
        sourceName = try c.decodeIfPresent(String.self, forKey: .sourceName)
        createDate = try c.decodeIfPresent(String.self, forKey: .createDate)
        lastUsed = try c.decodeIfPresent(String.self, forKey: .lastUsed)
        folderId = try c.decodeIfPresent(String.self, forKey: .folderId)
        artPath = try c.decodeIfPresent(String.self, forKey: .artPath)
        ownerMemberId = try c.decodeIfPresent(String.self, forKey: .ownerMemberId)
        ownerName = try c.decodeIfPresent(String.self, forKey: .ownerName)
        visibility = try c.decodeIfPresent(String.self, forKey: .visibility)
        sharedWith = try c.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
        daily = try c.decodeIfPresent(Bool.self, forKey: .daily) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(trackCount, forKey: .trackCount)
        try c.encodeIfPresent(source, forKey: .source)
        try c.encodeIfPresent(sourceName, forKey: .sourceName)
        try c.encodeIfPresent(createDate, forKey: .createDate)
        try c.encodeIfPresent(lastUsed, forKey: .lastUsed)
        try c.encodeIfPresent(folderId, forKey: .folderId)
        try c.encodeIfPresent(artPath, forKey: .artPath)
        try c.encodeIfPresent(ownerMemberId, forKey: .ownerMemberId)
        try c.encodeIfPresent(ownerName, forKey: .ownerName)
        try c.encodeIfPresent(visibility, forKey: .visibility)
        if !sharedWith.isEmpty { try c.encode(sharedWith, forKey: .sharedWith) }
        if daily { try c.encode(daily, forKey: .daily) }
    }
}

struct PlaylistDetailResponse: Decodable {
    var id: String = ""
    var name: String = ""
    var tracks: [PlaylistTrack] = []
    var total: Int = 0
    var page: Int = 1
    var source: String?
    var daily: Bool = false
    var dailyRecipe: String?
    var ownerMemberId: String?
    var ownerName: String?
    var visibility: String?
    var sharedWith: [String] = []
    var rated: Bool = false
    var stars: Int?

    enum CodingKeys: String, CodingKey {
        case id, name, tracks, total, page, source, daily, dailyRecipe
        case ownerMemberId, ownerName, visibility, sharedWith, rated, stars
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        tracks = try c.decodeIfPresent([PlaylistTrack].self, forKey: .tracks) ?? []
        total = try c.decodeIfPresent(Int.self, forKey: .total) ?? 0
        page = try c.decodeIfPresent(Int.self, forKey: .page) ?? 1
        source = try c.decodeIfPresent(String.self, forKey: .source)
        daily = try c.decodeIfPresent(Bool.self, forKey: .daily) ?? false
        dailyRecipe = try c.decodeIfPresent(String.self, forKey: .dailyRecipe)
        ownerMemberId = try c.decodeIfPresent(String.self, forKey: .ownerMemberId)
        ownerName = try c.decodeIfPresent(String.self, forKey: .ownerName)
        visibility = try c.decodeIfPresent(String.self, forKey: .visibility)
        sharedWith = try c.decodeIfPresent([String].self, forKey: .sharedWith) ?? []
        rated = try c.decodeIfPresent(Bool.self, forKey: .rated) ?? false
        stars = try c.decodeIfPresent(Int.self, forKey: .stars)
    }
}

struct PlaylistTrack: Codable, Identifiable {
    var title: String?
    var artist: String?
    var album: String?
    var path: String?
    var duration: Int?
    var stars: Int?

    var id: String { path ?? "\(title ?? "")-\(artist ?? "")" }

    enum CodingKeys: String, CodingKey {
        case title, artist, album, path, duration, stars
        case durationSeconds = "duration_seconds"
    }

    init(
        title: String? = nil,
        artist: String? = nil,
        album: String? = nil,
        path: String? = nil,
        duration: Int? = nil,
        stars: Int? = nil
    ) {
        self.title = title
        self.artist = artist
        self.album = album
        self.path = path
        self.duration = duration
        self.stars = stars
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        artist = try c.decodeIfPresent(String.self, forKey: .artist)
        album = try c.decodeIfPresent(String.self, forKey: .album)
        path = try c.decodeIfPresent(String.self, forKey: .path)
        duration = try c.decodeIfPresent(Int.self, forKey: .duration)
            ?? c.decodeIfPresent(Int.self, forKey: .durationSeconds)
        stars = try c.decodeIfPresent(Int.self, forKey: .stars)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(title, forKey: .title)
        try c.encodeIfPresent(artist, forKey: .artist)
        try c.encodeIfPresent(album, forKey: .album)
        try c.encodeIfPresent(path, forKey: .path)
        try c.encodeIfPresent(duration, forKey: .duration)
        try c.encodeIfPresent(stars, forKey: .stars)
    }
}

struct ArtistsResponse: Codable {
    var items: [ArtistItem] = []
    var total: Int = 0
}

struct ArtistItem: Codable, Identifiable {
    var artist: String = ""
    var track_count: Int = 0
    var album_count: Int = 0
    var art_path: String?

    var id: String { artist }
    var name: String { artist }
    var tracks: Int { track_count }
    var albums: Int { album_count }
    var artPath: String? { art_path }
}

struct ArtistPortraitResponse: Codable {
    var artist: String = ""
    var art_path: String?
    var source: String?
    var cached: Bool = false

    var artPath: String? { art_path }
}

struct AlbumsResponse: Codable {
    var items: [AlbumItem] = []
    var total: Int = 0
}

struct AlbumItem: Codable, Identifiable {
    var album: String = ""
    var artist: String?
    var track_count: Int = 0
    var year: Int?
    var art_path: String?
    var played: Bool = true

    var id: String { "\(album)-\(artist ?? "")" }
    var name: String { album }
    var tracks: Int { track_count }
    var artPath: String? { art_path }
    var unplayed: Bool { !played }
}

struct ArtistDetailTrack: Codable, Identifiable {
    var title: String?
    var artist: String?
    var album: String?
    var path: String?
    var duration_seconds: Int?
    var genre: String?
    var year: Int?
    var playCount: Int = 0
    var liked: Bool = false
    var rating: Int = 0

    var id: String { path ?? title ?? UUID().uuidString }
    var duration: Int? { duration_seconds }
}

struct ArtistDetailAbout: Codable {
    var firstAdded: String?
    var topDecade: Int?
    var topGenres: [String] = []
}

struct ArtistDetailAlbum: Codable, Identifiable {
    var album: String = ""
    var artist: String?
    var path: String?
    var year: Int?
    var track_count: Int = 0
    var first_seen_at: String?

    var id: String { "\(album)-\(artist ?? "")" }
    var name: String { album }
    var tracks: Int { track_count }
    var artPath: String? { path }

    func asAlbumItem() -> AlbumItem {
        AlbumItem(album: album, artist: artist, track_count: track_count, year: year, art_path: path)
    }
}

struct ArtistDetailSimilar: Codable {
    var artist: String = ""
    var path: String?
}

struct ArtistDetailResponse: Codable {
    var artist: String = ""
    var trackCount: Int = 0
    var albumCount: Int = 0
    var totalPlays: Int = 0
    var followed: Bool = false
    var rating: Int = 0
    var topTracks: [ArtistDetailTrack] = []
    var albums: [ArtistDetailAlbum] = []
    var similarArtists: [ArtistDetailSimilar] = []
    var appearsOn: [ArtistDetailAlbum] = []
    var about: ArtistDetailAbout?
}

struct MusicVideoRelatedItem: Codable, Identifiable {
    var videoId: String = ""
    var title: String = ""
    var artist: String = ""
    var thumbnail: String?

    var id: String { videoId }
}

struct MusicVideoRelatedResponse: Codable {
    var items: [MusicVideoRelatedItem] = []
}

struct WatchFolder: Codable, Identifiable {
    var guid: String?
    var path: String?
    var label: String?
    var status: String?
    var count: Int = 0
    var identifiedFiles: Int = 0
    var errors: Int = 0
    var playlists: Int = 0
    var type: String?

    var id: String { guid ?? path ?? label ?? UUID().uuidString }
}

struct GenresResponse: Codable {
    var items: [GenreItem] = []
    var total: Int = 0
}

struct HomeSectionPinDto: Codable {
    var sectionId: String = ""
    var playlistId: String = ""
    var playlistName: String = ""
    var pinnedAtMs: Int64 = 0
}

struct HomeDefaultsPolicy: Codable {
    var playlistsScope: String = "household"
    var playlistLimit: Int = 2000
    var genreLimit: Int = 80
}

struct HomeDefaultsResponse: Codable {
    var version: Int = 1
    var savedAt: String?
    var policy: HomeDefaultsPolicy = HomeDefaultsPolicy()
    var sectionPins: [HomeSectionPinDto] = []
}

struct HomeResponse: Codable {
    var history: StreamHistoryResponse?
    var dashboard: DashboardQuickResponse?
    var playlists: PlaylistsResponse?
    var smartPlaylists: SmartPlaylistsResponse?
    var genres: GenresResponse?
    var libraryNew: LibraryNewResponse?
    var followedLibraryNew: LibraryNewResponse?
    var discoverWeekly: DiscoverWeeklyResponse?
    var `continue`: ContinueResponse?
    var analytics: AnalyticsResponse?
    var ratings: RatingsResponse?
    var listeningSummary: AnalyticsResponse?
    var homeDefaults: HomeDefaultsResponse?
    var recentlyCreatedPlaylists: PlaylistsResponse?
}

struct GenreItem: Codable, Identifiable {
    var name: String = ""
    var track_count: Int = 0
    var art_path: String?

    var id: String { name }
}

struct SongsResponse: Codable {
    var items: [SongItem] = []
    var total: Int = 0
}

struct SongItem: Codable, Identifiable {
    var title: String?
    var artist: String?
    var album: String?
    var path: String?
    var duration: Int?
    var genre: String?
    var year: Int?
    var track_number: Int?
    var disc_number: Int?

    var id: String { path ?? "\(title ?? "")-\(artist ?? "")" }
    var trackNumber: Int? { track_number }
    var discNumber: Int? { disc_number }
}

struct AlexaDevicesResponse: Codable {
    var devices: [AlexaDevice] = []
}

struct AlexaDevice: Codable, Identifiable {
    var name: String?
    var serial: String?
    var online: Bool = false
    var family: String?

    var id: String { serial ?? name ?? UUID().uuidString }
}

struct DeviceItem: Codable, Identifiable {
    var deviceId: String = ""
    var name: String?
    var lastSeen: Double?
    var firstSeen: Double?

    var id: String { deviceId }
}

struct AutomationsResponse: Codable {
    var items: [AutomationItem] = []
}

struct AutomationItem: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var label: String
    var playlist: String?
    var playlistId: String?
    var playlistName: String?
    var device: String?
    var deviceName: String?
    var time: String?
    var volume: Int?
    var shuffle: Bool
    var enabled: Bool
    var `repeat`: String?
    var days: [Int]

    init(
        id: String = "",
        name: String = "",
        label: String = "",
        playlist: String? = nil,
        playlistId: String? = nil,
        playlistName: String? = nil,
        device: String? = nil,
        deviceName: String? = nil,
        time: String? = nil,
        volume: Int? = nil,
        shuffle: Bool = false,
        enabled: Bool = true,
        repeat: String? = nil,
        days: [Int] = []
    ) {
        self.id = id
        self.name = name
        self.label = label
        self.playlist = playlist
        self.playlistId = playlistId
        self.playlistName = playlistName
        self.device = device
        self.deviceName = deviceName
        self.time = time
        self.volume = volume
        self.shuffle = shuffle
        self.enabled = enabled
        self.repeat = `repeat`
        self.days = days
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        label = try c.decodeIfPresent(String.self, forKey: .label) ?? name
        playlist = try c.decodeIfPresent(String.self, forKey: .playlist)
        playlistId = try c.decodeIfPresent(String.self, forKey: .playlistId)
        playlistName = try c.decodeIfPresent(String.self, forKey: .playlistName)
        device = try c.decodeIfPresent(String.self, forKey: .device)
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName)
        time = try c.decodeIfPresent(String.self, forKey: .time)
        volume = try c.decodeIfPresent(Int.self, forKey: .volume)
        shuffle = try c.decodeIfPresent(Bool.self, forKey: .shuffle) ?? false
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
        `repeat` = try c.decodeIfPresent(String.self, forKey: .repeat)
        days = try c.decodeIfPresent([Int].self, forKey: .days) ?? []
    }
}

struct PlayResponse: Codable {
    var device: String?
    var text: String?
    var ok: Bool = false
    var error: String?
    var code: String?
}

struct PlaylistCoverResponse: Codable {
    var playlistId: String?
    var path: String?
}

struct PlaylistCoversBatchRequest: Codable {
    var ids: [String]
}

struct PlaylistCoversBatchResponse: Codable {
    var covers: [String: String]?
}

struct SmartPlaylistsResponse: Codable {
    var items: [SmartPlaylist] = []
}

struct SmartPlaylist: Codable, Identifiable, Hashable {
    var id: String = ""
    var name: String = ""
    var enabled: Bool = true
    var playlistId: String?
    var trackCount: Int?
    var lastRefresh: String?

    enum CodingKeys: String, CodingKey {
        case id, name, enabled, trackCount, lastRefresh
        case playlistId = "linkedPlaylistId"
    }
}

struct DeviceGroupsResponse: Codable {
    var items: [DeviceGroup] = []
}

struct DeviceGroup: Codable, Identifiable {
    var id: String = ""
    var name: String = ""
    var members: [GroupMember] = []
}

struct GroupMember: Codable {
    var serial: String = ""
    var name: String?
}

struct AnalyticsResponse: Codable {
    var totalPlays = 0
    var uniqueTracks = 0
    var uniqueArtists = 0
    var uniqueAlbums = 0
    var activity: AnalyticsActivity?
    var hourOfDay: [HourCount] = []
    var dayOfWeek: [DayCount] = []
    var heatmap: [[Int]]?
    var topArtists: [CountRow] = []
    var topAlbums: [CountRow] = []
    var topTracks: [CountRow] = []
    var topDevices: [CountRow] = []
    var topGenres: [CountRow] = []
    var topDecades: [DecadeRow] = []
    var listeningStreak: ListeningStreak?
    var currentStreak = 0
    var longestStreak = 0
    var catalogCoverage: CatalogCoverage?
    var repeatRate: RepeatRate?
    var mostActiveDay: MostActiveDay?
    var deviceBreakdown: [DeviceBreakdownRow] = []
}

struct AnalyticsActivity: Codable {
    var day: [ActivityPoint] = []
    var week: [ActivityPoint] = []
    var month: [ActivityPoint] = []
    var year: [ActivityPoint] = []
}

struct ActivityPoint: Codable {
    var label = ""
    var count = 0
}

struct HourCount: Codable {
    var hour = 0
    var count = 0
}

struct DayCount: Codable {
    var day = ""
    var count = 0
}

struct DecadeRow: Codable {
    var decade: String?
    var count = 0
}

struct ListeningStreak: Codable {
    var current = 0
    var longest = 0
}

struct CatalogCoverage: Codable {
    var heard = 0
    var total = 0
    var pct = 0.0
}

struct RepeatRate: Codable {
    var repeated = 0
    var total = 0
    var pct = 0.0
}

struct MostActiveDay: Codable {
    var date = ""
    var count = 0
}

struct DeviceBreakdownRow: Codable {
    var deviceId = ""
    var name = ""
    var platform = ""
    var plays = 0
    var downloads = 0
    var connects = 0
}

struct CountRow: Codable, Identifiable {
    var label: String?
    var name: String?
    var artist: String?
    var count: Int = 0

    var id: String { label ?? name ?? UUID().uuidString }
    var displayName: String { label ?? name ?? "—" }
}

struct IgnoredResponse: Codable {
    var items: [IgnoredTrack] = []
}

struct IgnoredTrack: Codable, Identifiable {
    var path: String = ""
    var title: String?
    var artist: String?
    var album: String?

    var id: String { path }
}

struct RoomsResponse: Codable {
    var rooms: [RoomItem] = []
}

struct RoomNowPlaying: Codable {
    var track: String?
    var artist: String?
    var album: String?
    var paused: Bool = false
}

struct RoomItem: Codable, Identifiable {
    var name: String?
    var serial: String?
    var deviceId: String?
    var pseudo: Bool = false
    var nowPlaying: RoomNowPlaying?

    var id: String { deviceId ?? serial ?? name ?? UUID().uuidString }
}

struct RecentResponse: Codable {
    var items: [RecentPlayRequestItem] = []
    var total: Int = 0
}

struct RecentPlayRequestItem: Codable, Identifiable {
    var heard: String?
    var found: String?
    var success: Bool = false
    var timestamp: String?

    var id: String { timestamp ?? UUID().uuidString }
}

struct VolumeResponse: Codable {
    var volume: Int?
}

struct LocalIpResponse: Codable {
    var ip: String?
}

struct PlexSyncStatusResponse: Codable {
    var lastRun: String?
    var lastSuccess: String?
    var playlistCount: Int?
    var logTail: [String] = []
    var running: Bool = false
}

struct MergeCandidate: Codable, Identifiable {
    var sourceId: String = ""
    var targetId: String = ""
    var sourceName: String?
    var targetName: String?
    var score: Double?

    var id: String { "\(sourceId)-\(targetId)" }
}

struct MergeCandidatesResponse: Codable {
    var candidates: [MergeCandidate] = []
}

struct IdentifyStatusResponse: Codable {
    var running: Bool = false
    var current: String?
    var done: Int = 0
    var total: Int = 0
}

struct ContinueResponse: Codable {
    var resume: ResumeEntry?
    var recent: [ResumeEntry] = []
}

struct ResumeEntry: Codable, Identifiable {
    var id: String?
    var filepath: String?
    var track: String?
    var artist: String?
    var album: String?
    var offsetMs: Int = 0
    var durationMs: Int = 0
    var progress: Double = 0
    var context: ResumeContext?
}

struct ResumeContext: Codable {
    var kind: String?
    var id: String?
    var name: String?
}

struct LibraryNewResponse: Codable {
    var since: String?
    var albums: [LibraryNewAlbum] = []
    var tracks: [LibraryNewTrack] = []
}

struct LibraryNewAlbum: Codable {
    var album: String?
    var artist: String?
    var path: String?
    var first_seen_at: String?
}

struct LibraryNewTrack: Codable {
    var title: String?
    var artist: String?
    var album: String?
    var path: String?
    var first_seen_at: String?
}

struct FollowedNotificationsResponse: Codable {
    var since: String?
    var albums: [LibraryNewAlbum] = []
    var tracks: [LibraryNewTrack] = []
    var unreadCount: Int = 0
    var followedCount: Int = 0
    var followedArtists: [String] = []
}

struct DiscoverWeeklyResponse: Codable {
    var memberId: String?
    var generatedAt: String?
    var sections: [DiscoverSection] = []
}

struct DiscoverSection: Codable, Identifiable {
    var id: String?
    var title: String?
    var reason: String?
    var tracks: [DiscoverTrack] = []
}

struct DiscoverTrack: Codable {
    var path: String?
    var title: String?
    var artist: String?
    var album: String?
}

struct PlaylistFoldersResponse: Codable {
    var folders: [PlaylistFolder] = []
    var assignments: [String: String] = [:]
}

struct PlaylistFolder: Codable, Identifiable {
    var id: String = ""
    var name: String = ""
    var parentId: String?
    var order: Int = 0
}

struct HandoffResponse: Codable {
    var ok: Bool = false
    var method: String?
    var filepath: String?
    var offsetMs: Int?
    var streamUrl: String?
    var warning: String?
    var error: String?
}

struct LoudnessAnalyzeStatus: Codable {
    var running: Bool = false
    var processed: Int = 0
    var total: Int = 0
    var lastError: String?
}

struct ListenAgentResponse: Codable {
    var name: String?
    var tracks: [PlaylistTrack] = []
    var trackCount: Int?
    var shuffle: Bool?
    var intent: String?
    var mode: String?
    var message: String?
    var source: String?
}

struct AiPlaylistResponse: Codable {
    var preview: [PlaylistTrack] = []
    var name: String?
    var id: String?
    var tracks: [PlaylistTrack] = []
    var trackCount: Int?
    var playlistId: String?
    var source: String?
    var prompt: String?
}

struct MixMuseStatusResponse: Codable {
    var configured: Bool = false
    var provider: String?
    var mode: String?
    var supportsOpenAi: Bool = false
    var supportsClaude: Bool = false
}

struct DiscoveryMixResponse: Codable {
    var name: String?
    var tracks: [PlaylistTrack] = []
    var trackCount: Int?
    var playlistId: String?
    var id: String?
    var source: String?
    var shuffle: Bool?
    var seed: DiscoverySeedInfo?
}

struct DiscoverySeedInfo: Codable {
    var path: String?
    var title: String?
    var artist: String?
}

struct AcquireSuggestion: Codable, Identifiable {
    var name: String = ""
    var mbid: String?
    var reasons: [String] = []
    var tags: [String] = []
    var inLibrary: Bool = false
    var musicbrainzUrl: String?

    var id: String { mbid ?? name }
}

struct AcquireSuggestResponse: Codable {
    var source: String?
    var seed: AcquireSeedInfo?
    var suggestions: [AcquireSuggestion] = []
    var note: String?
    var error: String?
}

struct AcquireSeedInfo: Codable {
    var kind: String?
    var artist: String?
    var resolvedName: String?
    var mbid: String?
    var artists: [String]?
}

struct LyricLine: Codable, Identifiable, Hashable {
    var timeMs: Int64 = 0
    var text: String = ""
    var id: String { "\(timeMs)-\(text)" }
}

struct TrackMetaResponse: Codable {
    var path: String?
    var title: String?
    var artist: String?
    var album: String?
    var genre: String?
    var year: Int?
}

struct LyricsResponse: Codable {
    var synced: Bool = false
    var lines: [LyricLine] = []
    var plain: String = ""
    var source: String?
}

struct MusicVideoResponse: Codable {
    var videoId: String?
    var title: String?
    var streamReady: Bool?
    var playUrl: String?
    var streamReason: String?
}

struct MusicVideoPrepareResult {
    var videoId: String?
    var playUrl: URL?
    var error: String?
}

struct MusicVideoPlayResponse: Codable {
    var ready: Bool = false
    var playUrl: String?
    var proxied: Bool = false
    var reason: String?
}
