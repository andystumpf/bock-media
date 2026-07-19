import Foundation

/// Spotify-style search: suggest @ 1 char, fast grouped search @ 2+.
enum UnifiedSearchCoordinator {
    static let debounceMs: UInt64 = 200_000_000
    static let timeoutSec: TimeInterval = 15
    static let searchLimit = 30
    static let preview = 5

    struct Outcome {
        let response: SearchResponse
        let fromCache: Bool
        let prefixExtension: SearchResponse?
    }

    static func resolveCached(_ query: String) -> Outcome? {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        if let hit = SearchQueryCache.get(trimmed) {
            return Outcome(response: hit, fromCache: true, prefixExtension: nil)
        }
        if trimmed.count >= 2, let prefix = SearchQueryCache.prefixExtension(trimmed) {
            return Outcome(response: prefix, fromCache: false, prefixExtension: prefix)
        }
        return nil
    }

    static func fetch(repository: BockMediaRepository, query: String, source: String?) async throws -> SearchResponse {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        return try await withThrowingTaskGroup(of: SearchResponse.self) { group in
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(Self.timeoutSec * 1_000_000_000))
                throw URLError(.timedOut)
            }
            group.addTask {
                if trimmed.count == 1 {
                    return try await repository.searchSuggest(q: trimmed)
                }
                return try await repository.search(
                    q: trimmed,
                    limit: searchLimit,
                    preview: preview,
                    source: source?.isEmpty == false ? source : nil,
                    fast: true,
                    includeResonance: false,
                    includeRooms: false
                )
            }
            let result = try await group.next()!
            group.cancelAll()
            return result
        }
    }

    static func userErrorMessage(_ error: Error, remoteOk: Bool) -> String {
        if !remoteOk { return "Search unavailable — check your connection" }
        if (error as? URLError)?.code == .timedOut { return "Search timed out — try again" }
        return error.localizedDescription.isEmpty ? "Search failed" : error.localizedDescription
    }
}
