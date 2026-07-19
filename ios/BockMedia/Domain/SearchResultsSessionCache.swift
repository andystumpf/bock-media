import Foundation

/// Preserves search UI state when drilling into artist/album detail and back.
enum SearchResultsSessionCache {
    static var query = ""
    static var results: SearchResponse?
    static var suggestions: [SearchSuggestion] = []

    static func hasFreshResults(_ q: String) -> Bool {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        return trimmed.count >= 2 && query == trimmed && results != nil
    }

    static func saveSnapshot(query q: String, results r: SearchResponse?, suggestions s: [SearchSuggestion]) {
        let trimmed = q.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        query = trimmed
        results = r
        suggestions = s
    }

    static func clear() {
        query = ""
        results = nil
        suggestions = []
    }
}

/// Passes discography payload into the discography route without serializing large lists.
enum ArtistDiscographyNavCache {
    static var albums: [AlbumItem] = []
    static var appearsOnNames: Set<String> = []
}

/// In-memory artist detail cache — avoids blank reload when revisiting within TTL.
enum ArtistDetailSessionCache {
    struct UiState: Equatable {
        var scrollIndex: Int = 0
        var scrollOffset: Int = 0
        var collapsedSections: Set<String> = []
        var selectedTab: String = "music"
    }

    private struct Entry {
        let detail: ArtistDetailResponse
        let portraitPath: String?
        let savedAt: Date
        var ui: UiState
    }

    private static let ttl: TimeInterval = 8 * 60
    private static var store: [String: Entry] = [:]

    private static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func get(_ name: String) -> (ArtistDetailResponse, String?)? {
        let k = key(name)
        guard let entry = store[k] else { return nil }
        if Date().timeIntervalSince(entry.savedAt) > ttl {
            store.removeValue(forKey: k)
            return nil
        }
        return (entry.detail, entry.portraitPath)
    }

    static func getUiState(_ name: String) -> UiState? {
        let k = key(name)
        guard let entry = store[k] else { return nil }
        if Date().timeIntervalSince(entry.savedAt) > ttl { return nil }
        return entry.ui
    }

    static func put(_ name: String, detail: ArtistDetailResponse, portraitPath: String?, ui: UiState? = nil) {
        let k = key(name)
        guard !k.isEmpty else { return }
        let prev = store[k]
        store[k] = Entry(
            detail: detail,
            portraitPath: portraitPath,
            savedAt: Date(),
            ui: ui ?? prev?.ui ?? UiState()
        )
    }

    static func saveUiState(_ name: String, ui: UiState) {
        let k = key(name)
        guard let entry = store[k] else { return }
        store[k] = Entry(
            detail: entry.detail,
            portraitPath: entry.portraitPath,
            savedAt: Date(),
            ui: ui
        )
    }

    static func shouldPrefetch(_ name: String) -> Bool {
        let k = key(name)
        return !k.isEmpty && store[k] == nil
    }
}
