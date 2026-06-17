import Foundation

enum HomeFeedCache {
    private static let ttlMs: Int64 = 90_000
    private static var feed: HomeFeed?
    private static var cachedAtMs: Int64 = 0

    static func getIfFresh() -> HomeFeed? {
        guard let cached = feed else { return nil }
        if Int64(Date().timeIntervalSince1970 * 1000) - cachedAtMs > ttlMs { return nil }
        return cached
    }

    static func put(_ value: HomeFeed) {
        guard !value.sections.isEmpty else { return }
        feed = value
        cachedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func invalidate() {
        feed = nil
        cachedAtMs = 0
    }
}
