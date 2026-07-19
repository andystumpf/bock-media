import Foundation

struct TileEngagementEntry: Codable, Equatable {
    let firstSeenMs: Int64
    var lastSelectedMs: Int64?
}

enum HomeTileEngagement {
    static let staleDays = 4
    static let staleMs: Int64 = Int64(staleDays) * 24 * 60 * 60 * 1000

    private static let defaultsKey = "home_tile_engagement"

    private static func load() -> [String: TileEngagementEntry] {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey),
              let decoded = try? JSONDecoder().decode([String: TileEngagementEntry].self, from: data) else {
            return [:]
        }
        return decoded
    }

    private static func save(_ map: [String: TileEngagementEntry]) {
        guard let data = try? JSONEncoder().encode(map) else { return }
        UserDefaults.standard.set(data, forKey: defaultsKey)
    }

    static func noteCardsPresent(_ cardIds: some Collection<String>) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var map = load()
        var changed = false
        for id in cardIds where map[id] == nil {
            map[id] = TileEngagementEntry(firstSeenMs: now)
            changed = true
        }
        if changed { save(map) }
    }

    static func recordSelection(cardId: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var map = load()
        if var existing = map[cardId] {
            existing.lastSelectedMs = now
            map[cardId] = existing
        } else {
            map[cardId] = TileEngagementEntry(firstSeenMs: now, lastSelectedMs: now)
        }
        save(map)
        Task { @MainActor in ClientPrefsSync.schedulePush() }
    }

    static func exportJson() -> String? {
        let map = load()
        if map.isEmpty { return nil }
        guard let data = try? JSONEncoder().encode(map) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func importJson(_ raw: String) {
        guard !raw.isEmpty,
              let data = raw.data(using: .utf8),
              let decoded = try? JSONDecoder().decode([String: TileEngagementEntry].self, from: data) else { return }
        save(decoded)
    }

    static func isStale(cardId: String, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        guard let entry = load()[cardId] else { return false }
        let anchor = entry.lastSelectedMs ?? entry.firstSeenMs
        return nowMs - anchor >= staleMs
    }

    #if DEBUG
    static func resetForTesting() {
        UserDefaults.standard.removeObject(forKey: defaultsKey)
    }

    static func putForTesting(cardId: String, entry: TileEngagementEntry) {
        var map = load()
        map[cardId] = entry
        save(map)
    }
    #endif
}

enum HomeTileRotation {
    static func apply(_ feed: HomeFeed, input: HomeFeedInput, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> HomeFeed {
        let cardIds = feed.sections.flatMap(\.cards).map(\.id)
        HomeTileEngagement.noteCardsPresent(cardIds)

        var usedPlaylistIds = Set<String>()
        var usedCardIds = Set<String>()
        var usedPlaylistNames = Set<String>()
        for card in feed.sections.flatMap(\.cards) {
            usedCardIds.insert(card.id)
            if let playlistId = card.playlistId { usedPlaylistIds.insert(playlistId) }
            usedPlaylistNames.insert(card.title.lowercased())
        }

        var rotationIndex = 0
        let sections = feed.sections.map { section in
            guard isRotatable(section.kind) else { return section }
            let cards = section.cards.map { card -> HomeCard in
                guard HomeTileEngagement.isStale(cardId: card.id, nowMs: nowMs) else { return card }
                guard let replacement = findReplacement(
                    kind: section.kind,
                    stale: card,
                    input: input,
                    usedPlaylistIds: usedPlaylistIds,
                    usedCardIds: usedCardIds,
                    usedPlaylistNames: usedPlaylistNames,
                    rotationIndex: rotationIndex
                ) else { return card }
                rotationIndex += 1
                usedCardIds.insert(replacement.id)
                if let playlistId = replacement.playlistId { usedPlaylistIds.insert(playlistId) }
                usedPlaylistNames.insert(replacement.title.lowercased())
                HomeTileEngagement.noteCardsPresent([replacement.id])
                return replacement
            }
            return HomeSection(id: section.id, title: section.title, kind: section.kind, cards: cards)
        }
        return HomeFeed(sections: sections)
    }

    private static func isRotatable(_ kind: HomeSectionKind) -> Bool {
        switch kind {
        // browseGenres is a genre directory — rotation would swap genre tiles
        // for unrelated playlists.
        case .favorites, .offline, .decade, .browseGenres: return false
        default: return true
        }
    }

    private static func findReplacement(
        kind: HomeSectionKind,
        stale: HomeCard,
        input: HomeFeedInput,
        usedPlaylistIds: Set<String>,
        usedCardIds: Set<String>,
        usedPlaylistNames: Set<String>,
        rotationIndex: Int
    ) -> HomeCard? {
        let seed = input.shuffleSeed &+ UInt64(rotationIndex &* 17) &+ UInt64(bitPattern: Int64(stale.id.hashValue))
        let pool = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, seed: seed)
            .filter { pl in
                pl.tracks > 0 &&
                    !usedPlaylistIds.contains(pl.id) &&
                    !usedPlaylistNames.contains(pl.name.lowercased()) &&
                    !HomeFeedRules.isSpecialHomePlaylistName(pl.name)
            }
        guard let playlist = pool.first else { return nil }
        let cardId = "pl-\(playlist.id)"
        guard !usedCardIds.contains(cardId) else { return nil }
        return HomeCard(
            id: cardId,
            title: playlist.name,
            subtitle: subtitle(for: kind, playlist: playlist),
            artPath: nil,
            playlistId: playlist.id,
            playTarget: .playlist(id: playlist.id, name: playlist.name),
            kind: kind
        )
    }

    private static func subtitle(for kind: HomeSectionKind, playlist: PlaylistSummary) -> String {
        switch kind {
        case .jumpBackIn: return "\(playlist.tracks) tracks · Suggested for you"
        case .topMixes, .exploreThemes, .mood, .dailyMixes: return "Suggested mix"
        case .radio: return "From your library"
        case .discover: return "\(playlist.tracks) tracks · Discover"
        case .recentPlaylists: return "\(playlist.tracks) tracks · Suggested for you"
        case .recentlyCreated: return "\(playlist.tracks) tracks · Recently created"
        default: return "\(playlist.tracks) tracks"
        }
    }
}
