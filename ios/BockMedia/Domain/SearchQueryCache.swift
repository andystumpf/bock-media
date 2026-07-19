import Foundation

/// Short-TTL in-memory cache so backspace/retype avoids repeat network calls.
enum SearchQueryCache {
    private static let ttlMs: Int64 = 45_000
    private static var query = ""
    private static var response: SearchResponse?
    private static var savedAtMs: Int64 = 0

    static func get(_ q: String) -> SearchResponse? {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        guard query == trimmed, let response else { return nil }
        guard Int64(Date().timeIntervalSince1970 * 1000) - savedAtMs <= ttlMs else { return nil }
        return response
    }

    static func prefixExtension(_ q: String) -> SearchResponse? {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else { return nil }
        guard let hit = response else { return nil }
        let cached = query
        guard cached.count >= 1, trimmed != cached else { return nil }
        guard Int64(Date().timeIntervalSince1970 * 1000) - savedAtMs <= ttlMs else { return nil }
        guard trimmed.hasPrefix(cached) else { return nil }
        return hit
    }

    static func put(_ q: String, _ response: SearchResponse) {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        query = trimmed
        self.response = response
        savedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func invalidate() {
        query = ""
        response = nil
        savedAtMs = 0
    }
}
