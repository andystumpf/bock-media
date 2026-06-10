import Foundation

struct OfflineTrackEntry: Codable, Equatable, Identifiable {
    var path: String
    var title: String
    var artist: String?
    var album: String?
    var fileName: String

    var id: String { path }
}

struct OfflineCollectionManifest: Codable, Equatable, Identifiable {
    var id: String
    var title: String
    var kind: String = "playlist"
    var sourcePlaylistId: String?
    var coverArtPath: String?
    var lastSyncedAtMs: Int64 = 0
    var downloadedAtMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    var tracks: [OfflineTrackEntry] = []

    var legacyPlaylistId: String? {
        sourcePlaylistId ?? (id.hasPrefix("pl-") ? String(id.dropFirst(3)) : nil)
    }

    func toPlayTarget() -> PlayTarget {
        if let pid = sourcePlaylistId, !pid.isEmpty, kind == "playlist" {
            return .playlist(id: pid, name: title)
        }
        switch kind {
        case "artist": return .artist(name: title)
        case "album": return .album(name: title, artist: tracks.first?.artist)
        case "song":
            if let t = tracks.first { return .song(path: t.path, title: t.title) }
            return .playlist(id: legacyPlaylistId ?? id, name: title)
        default:
            return .playlist(id: legacyPlaylistId ?? id.replacingOccurrences(of: "pl-", with: ""), name: title)
        }
    }
}

enum DownloadState: String, Codable {
    case idle, downloading, complete, failed
}

struct OfflineCollectionStatus: Identifiable, Equatable {
    var manifest: OfflineCollectionManifest
    var state: DownloadState
    var progress: Float = 0
    var error: String?

    var id: String { manifest.id }
}

func formatOfflineBytes(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    if bytes < 1024 * 1024 { return "\(bytes / 1024) KB" }
    if bytes < 1024 * 1024 * 1024 {
        return String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
    }
    return String(format: "%.2f GB", Double(bytes) / (1024 * 1024 * 1024))
}

func formatDownloadDate(ms: Int64) -> String {
    guard ms > 0 else { return "Unknown" }
    let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
    return date.formatted(date: .abbreviated, time: .omitted)
}
