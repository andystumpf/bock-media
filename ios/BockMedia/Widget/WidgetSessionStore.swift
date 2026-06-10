import Foundation

struct WidgetNowPlayingItem: Codable, Identifiable, Equatable {
    var deviceId: String
    var deviceName: String?
    var track: String?
    var artist: String?
    var paused: Bool
    var isLocal: Bool
    var canControl: Bool
    var artURL: String?

    var id: String { deviceId }

    init(
        deviceId: String,
        deviceName: String?,
        track: String?,
        artist: String?,
        paused: Bool,
        isLocal: Bool = false,
        canControl: Bool = false,
        artURL: String? = nil
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.track = track
        self.artist = artist
        self.paused = paused
        self.isLocal = isLocal
        self.canControl = canControl
        self.artURL = artURL
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceId = try container.decode(String.self, forKey: .deviceId)
        deviceName = try container.decodeIfPresent(String.self, forKey: .deviceName)
        track = try container.decodeIfPresent(String.self, forKey: .track)
        artist = try container.decodeIfPresent(String.self, forKey: .artist)
        paused = try container.decode(Bool.self, forKey: .paused)
        isLocal = try container.decodeIfPresent(Bool.self, forKey: .isLocal) ?? (deviceId == WidgetSnapshotLogic.localDeviceId)
        canControl = try container.decodeIfPresent(Bool.self, forKey: .canControl) ?? isLocal
        artURL = try container.decodeIfPresent(String.self, forKey: .artURL)
    }
}

/// Recently played playlist (or track) shown when nothing is currently playing.
struct WidgetRecentPlaylist: Codable, Identifiable {
    var id: String
    var title: String
    var subtitle: String?
    var playlistId: String?
    var playlistName: String?
    var songPath: String?
}

struct WidgetSessionSnapshot: Codable {
    var baseURL: String?
    var updatedAt: Date
    var items: [WidgetNowPlayingItem]
    var recentPlaylists: [WidgetRecentPlaylist]
    var controlsAvailable: Bool

    init(
        baseURL: String?,
        updatedAt: Date,
        items: [WidgetNowPlayingItem],
        recentPlaylists: [WidgetRecentPlaylist] = [],
        controlsAvailable: Bool = false
    ) {
        self.baseURL = baseURL
        self.updatedAt = updatedAt
        self.items = items
        self.recentPlaylists = recentPlaylists
        self.controlsAvailable = controlsAvailable
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        baseURL = try container.decodeIfPresent(String.self, forKey: .baseURL)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        items = try container.decode([WidgetNowPlayingItem].self, forKey: .items)
        recentPlaylists = try container.decodeIfPresent([WidgetRecentPlaylist].self, forKey: .recentPlaylists) ?? []
        controlsAvailable = try container.decodeIfPresent(Bool.self, forKey: .controlsAvailable) ?? false
    }
}

enum WidgetSessionStore {
    static let appGroupId = "group.com.bockmedia.console"
    private static let fileName = "now_playing_snapshot.json"

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupId)
    }

    static var isAvailable: Bool { containerURL != nil }

    private static var fileURL: URL? {
        containerURL?.appendingPathComponent(fileName, isDirectory: false)
    }

    static func save(_ snapshot: WidgetSessionSnapshot) {
        guard let url = fileURL,
              let data = try? JSONEncoder().encode(snapshot) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func load() -> WidgetSessionSnapshot? {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSessionSnapshot.self, from: data)
    }
}

enum WidgetRecentPlaylistLimit {
    static let max = 3
}

extension WidgetRecentPlaylist {
    var playURL: URL? {
        var components = URLComponents()
        components.scheme = "bockmedia"
        components.host = "play"
        if let playlistId, let playlistName {
            components.queryItems = [
                URLQueryItem(name: "playlistId", value: playlistId),
                URLQueryItem(name: "playlist", value: playlistName),
            ]
            return components.url
        }
        if let playlistName {
            components.queryItems = [URLQueryItem(name: "playlist", value: playlistName)]
            return components.url
        }
        if let songPath, !songPath.isEmpty {
            components.queryItems = [
                URLQueryItem(name: "path", value: songPath),
                URLQueryItem(name: "title", value: title),
            ]
            return components.url
        }
        return nil
    }
}
