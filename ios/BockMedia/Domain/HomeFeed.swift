import Foundation

enum HomeFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case recents = "Recents"
    case playlists = "Playlists"
    case mixes = "Mixes"
    case radio = "Radio"
    case discover = "Discover"
    case offline = "Offline"

    var id: String { rawValue }
    var label: String { rawValue }
}

enum HomeSectionKind {
    case jumpBackIn, favorites, topMixes, exploreThemes, mood, dailyMixes, recentPlaylists, radio, discover, offline
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

private let homeShortcutMixKinds: Set<HomeSectionKind> = [
    .topMixes, .mood, .dailyMixes, .exploreThemes, .recentPlaylists,
]

extension HomeCard {
    /// Quick-access tiles: playlists and mixes only — never albums or individual tracks.
    var eligibleForHomeShortcut: Bool {
        switch playTarget {
        case .album, .song:
            return false
        case .playlist(_, let name):
            return !HomeFeedRules.isAutomationPlaylistName(name)
        case .artist, .radio:
            return homeShortcutMixKinds.contains(kind)
        }
    }
}

extension HomeFeed {
    /// Up to `limit` shortcut tiles: recent playlists first, then backfilled from mix rows.
    /// Mirrors Android `HomeFeed.homeShortcutCards()`.
    func homeShortcutCards(limit: Int = 6) -> [HomeCard] {
        let jump = (sections.first { $0.kind == .jumpBackIn }?.cards ?? [])
            .filter { $0.eligibleForHomeShortcut }
        var seen = Set(jump.map { $0.id })
        var result = jump
        if result.count >= limit { return Array(result.prefix(limit)) }
        for section in sections where homeShortcutMixKinds.contains(section.kind) {
            for card in section.cards {
                guard card.eligibleForHomeShortcut, !seen.contains(card.id) else { continue }
                result.append(card)
                seen.insert(card.id)
                if result.count >= limit { return result }
            }
        }
        return result
    }
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
        async let genresTask = try? await repository.genres(limit: 40)
        async let continueTask = try? await repository.continueListening()
        async let newTask = try? await repository.libraryNew()
        async let discoverTask = try? await repository.discoverWeekly()

        let history = await historyTask?.items ?? []
        let analytics = await analyticsTask
        let allPlaylists = await playlistsTask?.items ?? []
        let smartPlaylists = await smartTask ?? []
        let dashboard = await dashboardTask
        let favoritesFallback = await favoritesTask ?? []
        let favorites = dashboard?.favorites.nilIfEmpty ?? favoritesFallback
        let libraryGenres = await genresTask ?? []
        let continueData = await continueTask
        let libraryNew = await newTask
        let discoverData = await discoverTask

        let releaseLabel: String? = {
            let n = libraryNew?.albums.count ?? 0
            guard n > 0 else { return nil }
            return "Added this week · \(n) album\(n == 1 ? "" : "s")"
        }()
        let discoverCards: [HomeCard] = (discoverData?.sections.first?.tracks ?? []).prefix(12).compactMap { t in
            guard let path = t.path else { return nil }
            return HomeCard(
                id: "dw-\(path)",
                title: t.title ?? path,
                subtitle: discoverData?.sections.first?.reason ?? "Discover Weekly",
                artPath: path,
                playlistId: nil,
                playTarget: .song(path: path, title: t.title ?? path),
                kind: .discover
            )
        }

        let shuffleSeed = UInt64(Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1)
        let input = HomeFeedInput(
            history: history,
            analytics: analytics,
            allPlaylists: allPlaylists,
            smartPlaylists: smartPlaylists,
            favorites: favorites,
            dashboard: dashboard,
            libraryGenres: libraryGenres,
            shuffleSeed: shuffleSeed,
            continueResume: continueData?.resume,
            releaseRadarLabel: releaseLabel,
            releaseRadarArtPath: libraryNew?.albums.first?.path,
            discoverWeeklyCards: discoverCards
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
