import Foundation

enum HomeFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case recents = "Recents"
    case playlists = "Playlists"
    case mixes = "Mixes"
    case radio = "Radio"
    case discover = "Discover"
    case offline = "Downloads"

    var id: String { rawValue }
    var label: String { rawValue }
}

enum HomeSectionKind {
    case jumpBackIn, favorites, ratedSongs, topMixes, browseGenres, exploreThemes, mood, decade, dailyMixes, recentlyCreated, recentPlaylists, radio, discover, offline
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
    /// Playlist id when this tile represents a library playlist (detail navigation).
    var linkedPlaylistId: String? {
        if let playlistId { return playlistId }
        if case .playlist(let id, _) = playTarget { return id }
        return nil
    }

    var browseDestination: HomeCardBrowse.Destination? {
        HomeCardBrowse.destination(for: self)
    }

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

    /// Playlist tiles use long-press to play; section rules hide play on browse/radio rows.
    func showsHomePlayOverlay(sectionKind: HomeSectionKind, sectionId: String) -> Bool {
        if sectionKind == .radio { return false }
        if sectionKind == .browseGenres { return false }
        if sectionId == "followed-releases" { return false }
        if case .playlist = playTarget { return false }
        return true
    }

    /// Hide download badge on browse/radio rows and followed-artist releases; playlists use long-press.
    func showsHomeDownloadOverlay(sectionKind: HomeSectionKind, sectionId: String) -> Bool {
        if sectionKind == .radio { return false }
        if sectionKind == .browseGenres { return false }
        if sectionId == "followed-releases" { return false }
        if case .playlist = playTarget { return false }
        return true
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

    func hasCurrentHomeLayout() -> Bool {
        sections.contains { $0.id == "recently-created" }
            && sections.contains { $0.id == "more-playlists" || $0.id == "browse-genres" || $0.id == "recent-playlists" }
    }

    /// Empty rated-songs section is omitted by the composer; usability must use `hasRatedSongs`,
    /// not section presence alone.
    func isUsableHomeCache(activeProfileLinked: Bool, hasRatedSongs: Bool? = nil) -> Bool {
        guard hasCurrentHomeLayout() else { return false }
        guard activeProfileLinked else { return true }
        if sections.contains(where: { $0.kind == .ratedSongs }) { return true }
        return hasRatedSongs != true
    }
}

func shouldRefreshHomeForProfile(activeProfileLinked: Bool, feed: HomeFeed?, hasRatedSongs: Bool?) -> Bool {
    guard activeProfileLinked, let feed else { return false }
    if feed.sections.contains(where: { $0.kind == .ratedSongs }) { return false }
    return hasRatedSongs == true
}

@MainActor
enum HomeFeedLoader {
    private static let playlistLimit = 500
    private static let genreLimit = 80

    static func load(repository: BockMediaRepository) async -> HomeFeed {
        let home = try? await repository.home(
            deferred: true,
            includeRatings: true,
            playlistLimit: playlistLimit,
            genreLimit: genreLimit
        )
        let analytics = home?.listeningSummary ?? home?.analytics
        let ratedItems = home?.ratings?.items ?? []

        if let h = home?.history { SessionDataStore.putHistory(h) }
        if let d = home?.dashboard { SessionDataStore.putDashboard(d) }
        if let a = analytics { SessionDataStore.putAnalytics(a) }
        let memberKey = ActiveProfileStore.activeMemberId() ?? ""
        if let pl = home?.playlists { SessionDataStore.putPlaylists(memberKey: memberKey, response: pl) }

        let history = home?.history?.items ?? []
        let allPlaylists = home?.playlists?.items ?? []
        let smartPlaylists = home?.smartPlaylists?.items ?? []
        let dashboard = home?.dashboard
        let favorites: [FavoriteItem] = dashboard?.favorites.nilIfEmpty ?? ratedItems.map {
            FavoriteItem(path: $0.id, title: $0.title, artist: $0.artist, album: $0.album)
        }
        let libraryGenres = home?.genres?.items ?? []
        let continueData = home?.continue
        let libraryNew = home?.libraryNew
        let followedLibraryNew = home?.followedLibraryNew
        let discoverData = home?.discoverWeekly

        let releaseLabel: String? = {
            let n = libraryNew?.albums.count ?? 0
            guard n > 0 else { return nil }
            return "Added this week · \(n) album\(n == 1 ? "" : "s")"
        }()
        let followedReleaseCards: [HomeCard] = (followedLibraryNew?.albums ?? []).prefix(12).compactMap { album in
            guard let name = album.album?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return nil }
            let artist = album.artist?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let subtitle = artist.isEmpty ? "New in library" : "\(artist) · New in library"
            return HomeCard(
                id: "followed-\(artist)-\(name)",
                title: name,
                subtitle: subtitle,
                artPath: album.path,
                playlistId: nil,
                playTarget: .album(name: name, artist: artist.isEmpty ? nil : artist),
                kind: .discover
            )
        }
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
            followedReleaseCards: followedReleaseCards,
            discoverWeeklyCards: discoverCards,
            ratedSongItems: ratedItems,
            recentlyCreatedPlaylists: home?.recentlyCreatedPlaylists?.items ?? []
        )
        let composed = HomeFeedComposer.compose(input)
        let rotated = HomeTileRotation.apply(composed, input: input)
        let serverPins = (home?.homeDefaults?.sectionPins ?? []).map {
            HomeSectionPin(
                sectionId: $0.sectionId,
                playlistId: $0.playlistId,
                playlistName: $0.playlistName,
                pinnedAtMs: $0.pinnedAtMs
            )
        }
        let mergedPins = HomeSectionPinsApplier.mergePins(server: serverPins, local: HomeSectionPinsStore.load())
        let result = HomeSectionPinsApplier.apply(rotated, pins: mergedPins, playlists: allPlaylists)
        HomeFeedCache.setHasRatedSongs(!ratedItems.isEmpty)
        return result
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
