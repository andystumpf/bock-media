import Foundation

enum DeepLink: Equatable {
    case home
    case search
    case library
    case nowPlaying(deviceId: String?)
    case downloads
    case settings
    case analytics
    case control(deviceId: String, action: String)
    case play(playlistId: String?, playlistName: String?, songPath: String?, songTitle: String?)

    static func parse(url: URL) -> DeepLink? {
        guard url.scheme == "bockmedia" else { return nil }
        let host = (url.host ?? url.pathComponents.dropFirst().first ?? "").lowercased()
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if host == "control" {
            guard let deviceId = query.first(where: { $0.name == "deviceId" })?.value,
                  let action = query.first(where: { $0.name == "action" })?.value else { return nil }
            return .control(deviceId: deviceId, action: action)
        }
        if host == "play" {
            return .play(
                playlistId: query.first(where: { $0.name == "playlistId" })?.value,
                playlistName: query.first(where: { $0.name == "playlist" })?.value,
                songPath: query.first(where: { $0.name == "path" })?.value,
                songTitle: query.first(where: { $0.name == "title" })?.value
            )
        }
        switch host {
        case "home", "": return .home
        case "search": return .search
        case "library": return .library
        case "nowplaying", "now-playing":
            return .nowPlaying(deviceId: query.first(where: { $0.name == "deviceId" })?.value)
        case "downloads": return .downloads
        case "settings": return .settings
        case "analytics": return .analytics
        default: return .home
        }
    }

    var tabIndex: Int {
        switch self {
        case .home, .nowPlaying, .downloads, .control, .play: return 0 // nowPlaying matches any associated value
        case .search: return 1
        case .library: return 2
        case .settings, .analytics: return 0
        }
    }
}
