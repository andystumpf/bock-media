import Foundation

struct SearchRecentSelection: Codable, Equatable {
    var kind: String
    var title: String
    var subtitle: String?
    var artist: String?
    var path: String?
    var playlistId: String?

    enum CodingKeys: String, CodingKey {
        case kind, title, subtitle, artist, path
        case playlistId = "id"
    }

    var selectionKey: String {
        [kind, playlistId, path, title, artist].compactMap { $0 }.joined(separator: "|")
    }

    static func fromArtist(_ name: String, albumCount: Int? = nil) -> SearchRecentSelection {
        SearchRecentSelection(
            kind: "artist",
            title: name,
            subtitle: albumCount.map { $0 == 1 ? "1 album" : "\($0) albums" }
        )
    }

    static func fromAlbum(_ name: String, artist: String?) -> SearchRecentSelection {
        SearchRecentSelection(kind: "album", title: name, subtitle: artist, artist: artist)
    }

    static func fromPlaylist(id: String, name: String) -> SearchRecentSelection {
        SearchRecentSelection(kind: "playlist", title: name, playlistId: id)
    }

    static func fromGenre(_ name: String) -> SearchRecentSelection {
        SearchRecentSelection(kind: "genre", title: name)
    }

    static func fromHit(kind: String, hit: SearchHit) -> SearchRecentSelection? {
        switch kind {
        case "artist":
            guard let name = hit.name else { return nil }
            return fromArtist(name, albumCount: hit.albums)
        case "album":
            guard let name = hit.name else { return nil }
            return fromAlbum(name, artist: hit.artist)
        case "playlist":
            guard let id = hit.id else { return nil }
            return fromPlaylist(id: id, name: hit.name ?? "Playlist")
        case "genre":
            guard let name = hit.name else { return nil }
            return fromGenre(name)
        case "song":
            let title = hit.title ?? hit.name ?? ""
            guard !title.isEmpty else { return nil }
            return SearchRecentSelection(kind: "song", title: title, subtitle: hit.artist, artist: hit.artist, path: hit.path)
        default:
            return nil
        }
    }
}

enum SearchHistoryStore {
    private static let selectionsKey = "search_history_selections"
    private static let legacyQueriesKey = "search_history_queries"
    private static let separator = "\u{0001}"
    private static let maxItems = 12

    static func selections() -> [SearchRecentSelection] {
        guard let data = UserDefaults.standard.data(forKey: selectionsKey) else { return [] }
        return (try? JSONDecoder().decode([SearchRecentSelection].self, from: data)) ?? []
    }

    static func replaceSelections(_ items: [SearchRecentSelection]) {
        saveSelections(Array(items.prefix(maxItems)))
    }

    static func addSelection(_ selection: SearchRecentSelection) {
        let trimmed = selection.title.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        var current = selections().filter { $0.selectionKey != selection.selectionKey }
        current.insert(selection, at: 0)
        saveSelections(Array(current.prefix(maxItems)))
        ClientPrefsSync.schedulePush()
    }

    static func removeSelection(_ selection: SearchRecentSelection) {
        saveSelections(selections().filter { $0.selectionKey != selection.selectionKey })
    }

    static func clearSelections() {
        UserDefaults.standard.removeObject(forKey: selectionsKey)
    }

    /// Legacy query strings — prefer [selections].
    static func queries() -> [String] {
        UserDefaults.standard.string(forKey: legacyQueriesKey)?
            .split(separator: Character(separator))
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty } ?? []
    }

    static func add(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else { return }
        addSelection(SearchRecentSelection(kind: "query", title: trimmed))
    }

    static func remove(_ query: String) {
        saveSelections(selections().filter { $0.title.caseInsensitiveCompare(query) != .orderedSame })
    }

    private static func saveSelections(_ items: [SearchRecentSelection]) {
        if items.isEmpty {
            UserDefaults.standard.removeObject(forKey: selectionsKey)
        } else if let data = try? JSONEncoder().encode(items) {
            UserDefaults.standard.set(data, forKey: selectionsKey)
        }
    }
}
