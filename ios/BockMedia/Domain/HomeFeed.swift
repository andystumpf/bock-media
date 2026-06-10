import Foundation

enum HomeFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case playlists = "Playlists"
    case mixes = "Mixes"
    case radio = "Radio"
    case discover = "Discover"
    case offline = "Offline"

    var id: String { rawValue }
    var label: String { rawValue }
}

enum HomeSectionKind {
    case jumpBackIn, favorites, topMixes, dailyMixes, recentPlaylists, radio, discover, offline
}

struct HomeCard: Identifiable {
    let id: String
    let title: String
    let subtitle: String?
    let artPath: String?
    let playlistId: String?
    let playTarget: PlayTarget
    let kind: HomeSectionKind
}

struct HomeSection: Identifiable {
    let id: String
    let title: String
    let kind: HomeSectionKind
    let cards: [HomeCard]
}

struct HomeFeed {
    let sections: [HomeSection]
}

@MainActor
enum HomeFeedLoader {
    private static let historyLimit = 150
    private static let playlistLimit = 500

    static func load(repository: BockMediaRepository) async -> HomeFeed {
        async let historyTask = try? await repository.streamHistory(limit: historyLimit)
        async let analyticsTask = analyticsWithTimeout(repository: repository)
        async let playlistsTask = try? await repository.playlists(limit: playlistLimit)
        async let smartTask = try? await repository.smartPlaylists()
        async let favoritesTask = try? await repository.favorites()
        async let dashboardTask = try? await repository.dashboardQuick()

        let history = await historyTask?.items ?? []
        let analytics = await analyticsTask
        let allPlaylists = await playlistsTask?.items ?? []
        let smartPlaylists = await smartTask ?? []
        let dashboard = await dashboardTask
        let favoritesFallback = await favoritesTask ?? []
        let favorites = dashboard?.favorites.nilIfEmpty ?? favoritesFallback

        let shuffleSeed = UInt64(Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1)
        let input = HomeFeedInput(
            history: history,
            analytics: analytics,
            allPlaylists: allPlaylists,
            smartPlaylists: smartPlaylists,
            favorites: favorites,
            dashboard: dashboard,
            shuffleSeed: shuffleSeed
        )
        let composed = HomeFeedComposer.compose(input)
        return HomeTileRotation.apply(composed, input: input)
    }

    static func offlineSection(store: OfflineDownloadStore) -> HomeSection? {
        let cards = store.listManifests()
            .filter { store.isCollectionComplete($0) }
            .sorted { ($0.lastSyncedAtMs > 0 ? $0.lastSyncedAtMs : $0.downloadedAtMs) > ($1.lastSyncedAtMs > 0 ? $1.lastSyncedAtMs : $1.downloadedAtMs) }
            .map { manifest in
                HomeCard(
                    id: "offline-\(manifest.id)",
                    title: manifest.title,
                    subtitle: "\(manifest.tracks.count) tracks · Available offline",
                    artPath: manifest.coverArtPath,
                    playlistId: manifest.sourcePlaylistId ?? manifest.legacyPlaylistId,
                    playTarget: manifest.toPlayTarget(),
                    kind: .offline
                )
            }
        guard !cards.isEmpty else { return nil }
        return HomeSection(id: "offline-library", title: "Your downloads", kind: .offline, cards: cards)
    }

    private static func analyticsWithTimeout(repository: BockMediaRepository) async -> AnalyticsResponse? {
        await withTaskGroup(of: AnalyticsResponse?.self) { group in
            group.addTask { try? await repository.analytics() }
            group.addTask {
                try? await Task.sleep(nanoseconds: 4_000_000_000)
                return nil
            }
            let first = await group.next()
            group.cancelAll()
            return first ?? nil
        }
    }
}

private extension Array {
    var nilIfEmpty: Self? { isEmpty ? nil : self }
}

private extension Sequence {
    func uniqued<Key: Hashable>(by key: (Element) -> Key) -> [Element] {
        var seen = Set<Key>()
        return filter { seen.insert(key($0)).inserted }
    }
}
