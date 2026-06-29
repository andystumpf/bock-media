import Foundation

enum LibraryPrefsStore {
    static func load(from prefs: AppPreferences = AppPreferences()) -> (filter: LibraryFilter, viewMode: LibraryViewMode, sort: LibrarySort) {
        (
            filter: parseTab(prefs.libraryTab) ?? .all,
            viewMode: parseViewMode(prefs.libraryViewMode) ?? .list,
            sort: parseSort(prefs.librarySortBy) ?? .recents
        )
    }

    static func save(
        filter: LibraryFilter,
        viewMode: LibraryViewMode,
        sort: LibrarySort,
        prefs: AppPreferences = AppPreferences(),
        push: Bool = true
    ) {
        prefs.libraryTab = tabValue(for: filter)
        prefs.libraryViewMode = viewMode == .grid ? "grid" : "list"
        prefs.librarySortBy = sort == .recents ? "recents" : "name"
        prefs.librarySortOrder = sort == .recents ? "desc" : "asc"
        if push {
            Task { @MainActor in ClientPrefsSync.schedulePush() }
        }
    }

    static func applyRemote(
        tab: String?,
        viewMode: String?,
        sortBy: String?,
        sortOrder: String?,
        prefs: AppPreferences = AppPreferences()
    ) {
        let current = load(from: prefs)
        save(
            filter: parseTab(tab) ?? current.filter,
            viewMode: parseViewMode(viewMode) ?? current.viewMode,
            sort: parseSort(sortBy) ?? current.sort,
            prefs: prefs,
            push: false
        )
        if let sortOrder, !sortOrder.isEmpty {
            prefs.librarySortOrder = sortOrder
        }
    }

    static func tabValue(for filter: LibraryFilter) -> String {
        switch filter {
        case .all: return "all"
        case .playlists: return "playlists"
        case .artists: return "artists"
        case .albums: return "albums"
        case .downloaded: return "downloaded"
        }
    }

    static func parseTab(_ raw: String?) -> LibraryFilter? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "all", "library": return .all
        case "playlists": return .playlists
        case "artists": return .artists
        case "albums": return .albums
        case "downloaded", "downloads": return .downloaded
        default: return nil
        }
    }

    static func parseViewMode(_ raw: String?) -> LibraryViewMode? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "grid": return .grid
        case "list": return .list
        default: return nil
        }
    }

    static func parseSort(_ raw: String?) -> LibrarySort? {
        switch raw?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "recents", "recent": return .recents
        case "name", "alphabetical": return .name
        default: return nil
        }
    }
}
