import Foundation

enum HomeFeedCache {
    private static let ttlMs: Int64 = 90_000
    private static var feed: HomeFeed?
    private static var cachedAtMs: Int64 = 0
    private static var hasRatedSongs: Bool?

    static func getIfFresh() -> HomeFeed? {
        guard let cached = feed else { return nil }
        if Int64(Date().timeIntervalSince1970 * 1000) - cachedAtMs > ttlMs { return nil }
        return cached
    }

    static func peek() -> HomeFeed? { feed }

    static func peekHasRatedSongs() -> Bool? { hasRatedSongs }

    static func put(_ value: HomeFeed, hasRatedSongs rated: Bool? = nil) {
        guard !value.sections.isEmpty else { return }
        feed = value
        cachedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
        if let rated { hasRatedSongs = rated }
    }

    static func setHasRatedSongs(_ value: Bool) {
        hasRatedSongs = value
    }

    static func invalidate() {
        feed = nil
        cachedAtMs = 0
        hasRatedSongs = nil
    }
}
