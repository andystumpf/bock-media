import Foundation

enum SearchBrowseSessionCache {
    private static let ttlMs: Int64 = 600_000
    private static var cachedFeed: SearchBrowseFeed?
    private static var cachedAtMs: Int64 = 0

    static func peek() -> SearchBrowseFeed? { cachedFeed }

    static func getIfFresh() -> SearchBrowseFeed? {
        guard let cachedFeed else { return nil }
        guard Int64(Date().timeIntervalSince1970 * 1000) - cachedAtMs <= ttlMs else { return nil }
        return cachedFeed
    }

    static func put(_ feed: SearchBrowseFeed) {
        guard !feed.pickedForYou.isEmpty || !feed.newReleases.isEmpty || !feed.genres.isEmpty else { return }
        cachedFeed = feed
        cachedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func invalidate() {
        cachedFeed = nil
        cachedAtMs = 0
    }
}
