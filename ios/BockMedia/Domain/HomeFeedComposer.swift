import Foundation

enum HomeFeedLimits {
    static let jumpBackIn = 24
    static let favorites = 16
    static let topMixes = 14
    static let dailyMixes = 12
    static let recentPlaylists = 24
    static let radio = 16
    static let discover = 24
    static let morePlaylists = 24
}

struct HomeFeedInput {
    let history: [StreamHistoryItem]
    let analytics: AnalyticsResponse?
    let allPlaylists: [PlaylistSummary]
    let smartPlaylists: [SmartPlaylist]
    let favorites: [FavoriteItem]
    let dashboard: DashboardQuickResponse?
    let shuffleSeed: UInt64
}

private struct HomeFeedRegistry {
    private(set) var usedPlaylistIds = Set<String>()
    private(set) var usedPlaylistNameKeys = Set<String>()
    private(set) var usedCardIds = Set<String>()
    var usedArtPaths = Set<String>()

    mutating func claimPlaylist(id: String, name: String) -> Bool {
        let nameKey = name.lowercased()
        guard !usedPlaylistIds.contains(id), !usedPlaylistNameKeys.contains(nameKey) else { return false }
        usedPlaylistIds.insert(id)
        usedPlaylistNameKeys.insert(nameKey)
        return true
    }

    mutating func registerCard(_ card: HomeCard) {
        usedCardIds.insert(card.id)
        if let playlistId = card.playlistId {
            usedPlaylistIds.insert(playlistId)
            usedPlaylistNameKeys.insert(card.title.lowercased())
        }
    }

    func hasCard(id: String) -> Bool {
        usedCardIds.contains(id)
    }

    func canUsePlaylist(_ playlist: PlaylistSummary) -> Bool {
        !usedPlaylistIds.contains(playlist.id) && !usedPlaylistNameKeys.contains(playlist.name.lowercased())
    }

    mutating func claimArtPath(_ path: String?) -> String? {
        guard let path, !path.isEmpty, !usedArtPaths.contains(path) else { return nil }
        usedArtPaths.insert(path)
        return path
    }
}

enum HomeFeedComposer {
    static func compose(_ input: HomeFeedInput) -> HomeFeed {
        var registry = HomeFeedRegistry()
        let playlistByName = Dictionary(
            input.allPlaylists.map { ($0.name.lowercased(), $0) },
            uniquingKeysWith: { _, last in last }
        )
        let playlistById = Dictionary(
            input.allPlaylists.map { ($0.id, $0) },
            uniquingKeysWith: { _, last in last }
        )

        var recentPlaylistNames: [String] = []
        var seenPlaylistNames = Set<String>()
        var artByPlaylist: [String: String] = [:]
        for row in input.history {
            guard let name = row.playlist?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { continue }
            let key = name.lowercased()
            if seenPlaylistNames.insert(key).inserted {
                recentPlaylistNames.append(name)
            }
            if let path = row.filepath {
                artByPlaylist[key] = artByPlaylist[key] ?? path
            }
        }

        let topGenres = Array(input.analytics?.topGenres.prefix(8) ?? [])
        let topArtists = input.analytics?.topArtists ?? []
        let shuffledGeneric = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, seed: input.shuffleSeed)

        func playlistCard(
            _ pl: PlaylistSummary,
            artPath: String? = nil,
            kind: HomeSectionKind,
            subtitle: String? = nil,
            claim: Bool = true
        ) -> HomeCard? {
            if claim, !registry.claimPlaylist(id: pl.id, name: pl.name) { return nil }
            let card = HomeCard(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: subtitle ?? "\(pl.tracks) tracks",
                artPath: artPath,
                playlistId: pl.id,
                playTarget: .playlist(id: pl.id, name: pl.name),
                kind: kind
            )
            registry.registerCard(card)
            return card
        }

        func fillPlaylists(
            from pool: [PlaylistSummary],
            target: Int,
            kind: HomeSectionKind,
            subtitle: (PlaylistSummary) -> String
        ) -> [HomeCard] {
            var cards: [HomeCard] = []
            for pl in pool where pl.tracks > 0 {
                guard cards.count < target else { break }
                guard registry.canUsePlaylist(pl) else { continue }
                if let card = playlistCard(
                    pl,
                    kind: kind,
                    subtitle: subtitle(pl)
                ) {
                    cards.append(card)
                }
            }
            return cards
        }

        func resolveMixArt(genre: String, artist: String?, index: Int) -> String? {
            if let p = registry.claimArtPath(HomeFeedRules.artPathForGenreDistinct(input.history, genre: genre, used: registry.usedArtPaths)) { return p }
            if let a = artist, let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, artist: a, used: registry.usedArtPaths)) { return p }
            if let a = topArtists[safe: index]?.name ?? topArtists[safe: index]?.label,
               let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, artist: a, used: registry.usedArtPaths)) { return p }
            return registry.claimArtPath(HomeFeedRules.nextDistinctArtPath(input.history, used: registry.usedArtPaths))
        }

        // MARK: Jump back in

        var jumpBackIn: [HomeCard] = []
        for card in dashboardJumpCards(input.dashboard, playlistByName: playlistByName, artByPlaylist: artByPlaylist) {
            guard jumpBackIn.count < HomeFeedLimits.jumpBackIn else { break }
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            jumpBackIn.append(card)
        }

        for name in recentPlaylistNames {
            guard jumpBackIn.count < HomeFeedLimits.jumpBackIn else { break }
            guard let pl = playlistByName[name.lowercased()] else { continue }
            if let card = playlistCard(pl, artPath: artByPlaylist[name.lowercased()], kind: .jumpBackIn, subtitle: "Recently played") {
                jumpBackIn.append(card)
            }
        }

        var seenAlbums = Set<String>()
        for row in input.history {
            guard jumpBackIn.count < HomeFeedLimits.jumpBackIn else { break }
            guard let album = row.album?.trimmingCharacters(in: .whitespaces), !album.isEmpty else { continue }
            let key = "\(album.lowercased())|\(row.artist?.lowercased() ?? "")"
            guard seenAlbums.insert(key).inserted else { continue }
            let card = HomeCard(
                id: "album-\(key)",
                title: album,
                subtitle: row.artist ?? "Recently played album",
                artPath: row.filepath,
                playlistId: nil,
                playTarget: .album(name: album, artist: row.artist),
                kind: .jumpBackIn
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            jumpBackIn.append(card)
        }

        let recentlyAddedPool = input.allPlaylists
            .sorted { parseSortDate($0.createDate) > parseSortDate($1.createDate) }
        jumpBackIn.append(contentsOf: fillPlaylists(
            from: recentlyAddedPool,
            target: HomeFeedLimits.jumpBackIn - jumpBackIn.count,
            kind: .jumpBackIn,
            subtitle: { _ in "Recently added" }
        ))

        jumpBackIn.append(contentsOf: fillPlaylists(
            from: shuffledGeneric,
            target: HomeFeedLimits.jumpBackIn - jumpBackIn.count,
            kind: .jumpBackIn,
            subtitle: { pl in "\(pl.tracks) tracks · From your library" }
        ))
        jumpBackIn = jumpBackIn.uniqued(by: \.id).prefix(HomeFeedLimits.jumpBackIn).map { $0 }

        // MARK: Favorites

        var favoriteCards: [HomeCard] = []
        for fav in input.favorites {
            guard favoriteCards.count < HomeFeedLimits.favorites else { break }
            let card = HomeCard(
                id: "fav-\(fav.path)",
                title: fav.title ?? fav.path,
                subtitle: fav.artist ?? "Liked song",
                artPath: fav.path,
                playlistId: nil,
                playTarget: .song(path: fav.path, title: fav.title ?? fav.path),
                kind: .favorites
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            favoriteCards.append(card)
        }

        // MARK: Top mixes

        var genreMixes: [HomeCard] = []
        for (index, row) in topGenres.enumerated() {
            guard genreMixes.count < HomeFeedLimits.topMixes else { break }
            let genre = row.name ?? row.label ?? ""
            guard !genre.isEmpty else { continue }
            if let smart = input.smartPlaylists.first(where: { sp in
                !HomeFeedRules.isDailyMixName(sp.name) && sp.name.localizedCaseInsensitiveContains(genre)
            }), let linkedId = smart.playlistId, let pl = playlistById[linkedId], !HomeFeedRules.isDailyMixName(pl.name),
               let card = playlistCard(pl, kind: .topMixes, subtitle: "\(genre) mix") {
                genreMixes.append(card)
                continue
            }
            if let named = input.allPlaylists.first(where: { HomeFeedRules.isGenreMixPlaylistName($0.name, genre: genre) })
                ?? input.allPlaylists.first(where: { $0.name.caseInsensitiveCompare("\(genre) Mix") == .orderedSame }),
               let card = playlistCard(named, kind: .topMixes, subtitle: "\(genre) mix") {
                genreMixes.append(card)
                continue
            }
            guard let seed = HomeFeedRules.topArtistForGenre(input.history, genre: genre)
                ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label
                ?? topArtists.first?.name ?? topArtists.first?.label else { continue }
            let card = HomeCard(
                id: "mix-\(genre)",
                title: "\(genre) Mix",
                subtitle: "Based on your listening",
                artPath: resolveMixArt(genre: genre, artist: seed, index: index),
                playlistId: nil,
                playTarget: .artist(name: seed),
                kind: .topMixes
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            genreMixes.append(card)
        }

        let genreMixPlaylistPool = input.allPlaylists
            .filter { HomeFeedRules.isGenreMixPlaylistName($0.name) && !HomeFeedRules.isDailyMixName($0.name) }
            .sorted { $0.tracks > $1.tracks }
        genreMixes.append(contentsOf: fillPlaylists(
            from: genreMixPlaylistPool + shuffledGeneric,
            target: HomeFeedLimits.topMixes - genreMixes.count,
            kind: .topMixes,
            subtitle: { pl in
                if HomeFeedRules.isGenreMixPlaylistName(pl.name) { return "Curated mix" }
                return "\(pl.tracks) tracks · Suggested mix"
            }
        ))

        // MARK: Daily mixes

        var dailyMixes: [HomeCard] = []
        for sp in input.smartPlaylists where sp.enabled && HomeFeedRules.isDailyMixName(sp.name) {
            guard dailyMixes.count < HomeFeedLimits.dailyMixes else { break }
            guard let pl = sp.playlistId.flatMap({ playlistById[$0] }) else { continue }
            if let card = playlistCard(pl, kind: .dailyMixes, subtitle: "Daily mix") {
                dailyMixes.append(card)
            }
        }
        let dailyNamed = input.allPlaylists
            .filter { HomeFeedRules.isDailyMixName($0.name) }
            .sorted { parseSortDate($0.lastUsed) > parseSortDate($1.lastUsed) }
        for pl in dailyNamed {
            guard dailyMixes.count < HomeFeedLimits.dailyMixes else { break }
            if let card = playlistCard(pl, kind: .dailyMixes, subtitle: "Daily mix") {
                dailyMixes.append(card)
            }
        }
        let mixLikePool = input.allPlaylists.filter {
            HomeFeedRules.isGenreMixPlaylistName($0.name) || $0.name.localizedCaseInsensitiveContains("mix")
        }.sorted { $0.tracks > $1.tracks }
        dailyMixes.append(contentsOf: fillPlaylists(
            from: mixLikePool + shuffledGeneric,
            target: HomeFeedLimits.dailyMixes - dailyMixes.count,
            kind: .dailyMixes,
            subtitle: { _ in "Mix playlist" }
        ))
        dailyMixes = dailyMixes.uniqued(by: \.id).prefix(HomeFeedLimits.dailyMixes).map { $0 }

        // MARK: Recent playlists

        var recentPlaylists: [HomeCard] = []
        for name in recentPlaylistNames {
            guard recentPlaylists.count < HomeFeedLimits.recentPlaylists else { break }
            guard let pl = playlistByName[name.lowercased()] else { continue }
            if let card = playlistCard(pl, artPath: artByPlaylist[name.lowercased()], kind: .recentPlaylists, subtitle: "Played recently") {
                recentPlaylists.append(card)
            }
        }
        let recentlyUsedPool = input.allPlaylists
            .sorted { parseSortDate($0.lastUsed) > parseSortDate($1.lastUsed) }
        recentPlaylists.append(contentsOf: fillPlaylists(
            from: recentlyUsedPool + shuffledGeneric,
            target: HomeFeedLimits.recentPlaylists - recentPlaylists.count,
            kind: .recentPlaylists,
            subtitle: { _ in "From your library" }
        ))

        // MARK: Radio

        let radioCards = buildRadioCards(
            history: input.history,
            topArtists: topArtists,
            topGenres: topGenres,
            allPlaylists: input.allPlaylists,
            registry: &registry,
            limit: HomeFeedLimits.radio
        )

        // MARK: Discover

        var discoverCandidates: [HomeCard] = []
        let discoverNamed = input.allPlaylists
            .filter { HomeFeedRules.isDiscoverName($0.name) }
            .sorted { parseSortDate($0.createDate) > parseSortDate($1.createDate) }
        for pl in discoverNamed {
            guard discoverCandidates.count < HomeFeedLimits.discover else { break }
            if let card = playlistCard(pl, kind: .discover, subtitle: "Discover Weekly") {
                discoverCandidates.append(card)
            }
        }
        for sp in input.smartPlaylists where sp.enabled {
            guard discoverCandidates.count < HomeFeedLimits.discover else { break }
            guard let pl = sp.playlistId.flatMap({ playlistById[$0] }) else { continue }
            let subtitle = HomeFeedRules.isDiscoverName(sp.name) ? "New to you" : "Smart playlist"
            if let card = playlistCard(pl, kind: .discover, subtitle: subtitle) {
                discoverCandidates.append(card)
            }
        }
        let largePlaylists = input.allPlaylists
            .filter { !HomeFeedRules.isSpecialHomePlaylistName($0.name) }
            .sorted { $0.tracks > $1.tracks }
        discoverCandidates.append(contentsOf: fillPlaylists(
            from: largePlaylists + shuffledGeneric,
            target: HomeFeedLimits.discover - discoverCandidates.count,
            kind: .discover,
            subtitle: { pl in "\(pl.tracks) tracks · Discover" }
        ))
        discoverCandidates = discoverCandidates.uniqued(by: \.id).prefix(HomeFeedLimits.discover).map { $0 }

        // MARK: More playlists

        let morePlaylists = fillPlaylists(
            from: shuffledGeneric + input.allPlaylists.sorted { $0.tracks > $1.tracks },
            target: HomeFeedLimits.morePlaylists,
            kind: .recentPlaylists,
            subtitle: { pl in "\(pl.tracks) tracks · Suggested for you" }
        )

        let sections = [
            section("jump-back-in", "Jump back in", .jumpBackIn, jumpBackIn),
            section("favorites", "Your favorites", .favorites, favoriteCards),
            section("top-mixes", "Your top mixes", .topMixes, genreMixes),
            section("daily-mixes", "Daily mixes", .dailyMixes, dailyMixes),
            section("recent-playlists", "Recent playlists", .recentPlaylists, recentPlaylists),
            section("radio", "Radio", .radio, radioCards),
            section("discover", "Discover", .discover, discoverCandidates),
            section("more-playlists", "More playlists", .recentPlaylists, morePlaylists),
        ].compactMap { $0 }

        return HomeFeed(sections: sections)
    }

    private static func section(_ id: String, _ title: String, _ kind: HomeSectionKind, _ cards: [HomeCard]) -> HomeSection? {
        guard !cards.isEmpty else { return nil }
        return HomeSection(id: id, title: title, kind: kind, cards: cards)
    }

    private static func parseSortDate(_ value: String?) -> Date {
        guard let value, !value.isEmpty else { return .distantPast }
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = iso.date(from: value) { return d }
        iso.formatOptions = [.withFullDate]
        if let d = iso.date(from: value) { return d }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.date(from: value) ?? .distantPast
    }

    private static func artPathForPlaylistSeed(_ history: [StreamHistoryItem], _ playlistName: String) -> String? {
        history.first { row in
            row.filepath != nil && row.playlist?.caseInsensitiveCompare(playlistName) == .orderedSame
        }?.filepath
    }

    private static func dashboardJumpCards(
        _ dashboard: DashboardQuickResponse?,
        playlistByName: [String: PlaylistSummary],
        artByPlaylist: [String: String]
    ) -> [HomeCard] {
        guard let dashboard else { return [] }
        return dashboard.recent.compactMap { item -> HomeCard? in
            if let playlist = item.playlist?.trimmingCharacters(in: .whitespaces), !playlist.isEmpty,
               let pl = playlistByName[playlist.lowercased()] {
                return HomeCard(
                    id: "pl-\(pl.id)",
                    title: pl.name,
                    subtitle: item.artist ?? "Recently played",
                    artPath: artByPlaylist[playlist.lowercased()] ?? item.path,
                    playlistId: pl.id,
                    playTarget: .playlist(id: pl.id, name: pl.name),
                    kind: .jumpBackIn
                )
            }
            guard let path = item.path, let title = item.track?.trimmingCharacters(in: .whitespaces), !title.isEmpty else { return nil }
            return HomeCard(
                id: "dash-\(path)",
                title: title,
                subtitle: item.artist ?? "Recently played",
                artPath: path,
                playlistId: nil,
                playTarget: .song(path: path, title: title),
                kind: .jumpBackIn
            )
        }
    }

    private static func buildRadioCards(
        history: [StreamHistoryItem],
        topArtists: [CountRow],
        topGenres: [CountRow],
        allPlaylists: [PlaylistSummary],
        registry: inout HomeFeedRegistry,
        limit: Int
    ) -> [HomeCard] {
        func resolveRadioArt(preferred: String?, artist: String?, index: Int) -> String? {
            if let p = registry.claimArtPath(preferred) { return p }
            if let a = artist, let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, artist: a, used: registry.usedArtPaths)) { return p }
            if let a = topArtists[safe: index]?.name ?? topArtists[safe: index]?.label,
               let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(history, artist: a, used: registry.usedArtPaths)) { return p }
            return registry.claimArtPath(HomeFeedRules.nextDistinctArtPath(history, used: registry.usedArtPaths))
        }

        var cards: [HomeCard] = []
        for (index, row) in topArtists.prefix(8).enumerated() {
            guard cards.count < limit else { break }
            let artist = row.name ?? row.label ?? ""
            guard !artist.isEmpty else { continue }
            let card = HomeCard(
                id: "radio-artist-\(artist)",
                title: "\(artist) Radio",
                subtitle: "Infinite · artist seed",
                artPath: resolveRadioArt(preferred: nil, artist: artist, index: index),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(artist) Radio", seedKind: .artist, name: artist, path: nil),
                kind: .radio
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            cards.append(card)
        }
        let songSeeds = history.filter { $0.filepath != nil && !($0.track?.isEmpty ?? true) }.uniqued(by: \.filepath).prefix(4)
        for (index, row) in songSeeds.enumerated() {
            guard cards.count < limit else { break }
            let title = row.track ?? "Song"
            let card = HomeCard(
                id: "radio-song-\(row.filepath ?? title)",
                title: title,
                subtitle: [row.artist, "Song radio"].compactMap { $0 }.joined(separator: " · "),
                artPath: resolveRadioArt(preferred: row.filepath, artist: row.artist, index: index),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(title) Radio", seedKind: .song, name: title, path: row.filepath),
                kind: .radio
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            cards.append(card)
        }
        for (index, row) in topGenres.prefix(6).enumerated() {
            guard cards.count < limit else { break }
            let genre = row.name ?? row.label ?? ""
            guard !genre.isEmpty,
                  let seed = HomeFeedRules.topArtistForGenre(history, genre: genre) ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label else { continue }
            let card = HomeCard(
                id: "radio-genre-\(genre)",
                title: "\(genre) Radio",
                subtitle: "Infinite · genre seed",
                artPath: resolveRadioArt(preferred: HomeFeedRules.artPathForGenreDistinct(history, genre: genre, used: registry.usedArtPaths), artist: seed, index: index),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(genre) Radio", seedKind: .genre, name: seed, path: nil),
                kind: .radio
            )
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            cards.append(card)
        }
        for pl in allPlaylists.filter({ HomeFeedRules.isExplicitRadioPlaylistName($0.name) }).sorted(by: { $0.tracks > $1.tracks }) {
            guard cards.count < limit else { break }
            guard registry.canUsePlaylist(pl) else { continue }
            let card = HomeCard(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: "Radio station",
                artPath: nil,
                playlistId: pl.id,
                playTarget: .playlist(id: pl.id, name: pl.name),
                kind: .radio
            )
            registry.registerCard(card)
            cards.append(card)
        }
        return cards.uniqued(by: \.id).prefix(limit).map { $0 }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

private extension Sequence {
    func uniqued<Key: Hashable>(by key: (Element) -> Key) -> [Element] {
        var seen = Set<Key>()
        return filter { seen.insert(key($0)).inserted }
    }
}
