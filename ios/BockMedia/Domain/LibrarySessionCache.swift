import Foundation

enum LibrarySessionCache {
    private static let ttlMs: Int64 = 90_000
    private static var data: LibraryData?
    private static var cachedAtMs: Int64 = 0

    static func getIfFresh() -> LibraryData? {
        guard let cached = data else { return nil }
        if Int64(Date().timeIntervalSince1970 * 1000) - cachedAtMs > ttlMs { return nil }
        return cached
    }

    static func peek() -> LibraryData? { data }

    static func put(_ value: LibraryData) {
        if value.playlists.isEmpty && value.artists.isEmpty && value.albums.isEmpty { return }
        data = value
        cachedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func invalidate() {
        data = nil
        cachedAtMs = 0
    }
}
