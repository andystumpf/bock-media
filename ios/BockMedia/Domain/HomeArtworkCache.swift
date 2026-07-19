import Foundation

/// Session cache for home card artwork URLs and playlist cover paths (avoids repeat API work).
enum HomeArtworkCache {
    private static let ttl: TimeInterval = 6 * 60 * 60
    private static var cardURLs: [String: URL] = [:]
    private static var playlistPaths: [String: String] = [:]
    private static var cachedAt: Date?

    private static var isFresh: Bool {
        guard let cachedAt else { return false }
        return Date().timeIntervalSince(cachedAt) < ttl
    }

    static func url(for cardId: String) -> URL? {
        guard isFresh else { return nil }
        return cardURLs[cardId]
    }

    static func urls(for cardIds: [String]) -> [String: URL] {
        guard isFresh else { return [:] }
        return cardIds.reduce(into: [:]) { partial, id in
            if let url = cardURLs[id] { partial[id] = url }
        }
    }

    static func playlistPath(id: String) -> String? {
        isFresh ? playlistPaths[id] : nil
    }

    static func store(cardId: String, url: URL) {
        touch()
        cardURLs[cardId] = url
    }

    static func storePlaylistPath(id: String, path: String) {
        touch()
        playlistPaths[id] = path
    }

    static func storePlaylistPaths(_ paths: [String: String]) {
        guard !paths.isEmpty else { return }
        touch()
        playlistPaths.merge(paths) { _, new in new }
    }

    static func merge(_ urls: [String: URL]) {
        guard !urls.isEmpty else { return }
        touch()
        cardURLs.merge(urls) { _, new in new }
    }

    static func invalidate() {
        cardURLs = [:]
        playlistPaths = [:]
        cachedAt = nil
    }

    static func allPlaylistPaths() -> [String: String] {
        isFresh ? playlistPaths : [:]
    }

    static func restore(playlistPaths paths: [String: String]) {
        guard !paths.isEmpty else { return }
        touch()
        playlistPaths = paths
    }

    private static func touch() {
        if cachedAt == nil { cachedAt = Date() }
    }
}
