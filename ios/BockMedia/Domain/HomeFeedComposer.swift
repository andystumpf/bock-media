import Foundation

enum HomeFeedLimits {
    static let jumpBackIn = 24
    static let favorites = 16
    static let topMixes = 16
    static let moodSectionMin = 9
    static let moodSectionCards = 500
    static let exploreThemes = 18
    static let libraryGenreExtras = 6
    static let dailyMixes = 12
    static let recentPlaylists = 24
    static let radio = 16
    static let discover = 24
    static let morePlaylists = 60
}

struct HomeFeedInput {
    let history: [StreamHistoryItem]
    let analytics: AnalyticsResponse?
    let allPlaylists: [PlaylistSummary]
    let smartPlaylists: [SmartPlaylist]
    let favorites: [FavoriteItem]
    let dashboard: DashboardQuickResponse?
    let libraryGenres: [GenreItem]
    let shuffleSeed: UInt64
    var continueResume: ResumeEntry? = nil
    var releaseRadarLabel: String? = nil
    var releaseRadarArtPath: String? = nil
    var discoverWeeklyCards: [HomeCard] = []
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
        if let path = card.artPath, !path.isEmpty {
            usedArtPaths.insert(path)
        }
        if let playlistId = card.playlistId {
            usedPlaylistIds.insert(playlistId)
            usedPlaylistNameKeys.insert(card.title.lowercased())
        }
    }

    mutating func registerMoodCard(_ card: HomeCard) {
        usedCardIds.insert(card.id)
    }

    mutating func reserveMoodPlaylists(_ cards: [HomeCard]) {
        for card in cards {
            if let playlistId = card.playlistId {
                usedPlaylistIds.insert(playlistId)
                usedPlaylistNameKeys.insert(card.title.lowercased())
            }
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
            guard let name = row.playlist?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
                  !HomeFeedRules.isAutomationPlaylistName(name) else { continue }
            let key = name.lowercased()
            if seenPlaylistNames.insert(key).inserted {
                recentPlaylistNames.append(name)
            }
            if let path = row.filepath {
                artByPlaylist[key] = artByPlaylist[key] ?? path
            }
        }

        let topGenres = Array(input.analytics?.topGenres.prefix(12) ?? [])
        let topArtists = input.analytics?.topArtists ?? []
        let shuffledGeneric = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, seed: input.shuffleSeed)

        func resolvePlaylistArt(_ pl: PlaylistSummary, genreHint: String? = nil) -> String? { nil }

        func playlistCard(
            _ pl: PlaylistSummary,
            artPath: String? = nil,
            kind: HomeSectionKind,
            subtitle: String? = nil,
            claim: Bool = true,
            genreHint: String? = nil
        ) -> HomeCard? {
            if HomeFeedRules.isAutomationPlaylistName(pl.name) { return nil }
            if claim, !registry.claimPlaylist(id: pl.id, name: pl.name) { return nil }
            // Prefer the playlist's own cover (first track, from /api/playlists) so the tile
            // renders from the cached feed without a per-tile cover lookup.
            let resolvedArt = artPath.flatMap { registry.claimArtPath($0) } ?? pl.artPath ?? resolvePlaylistArt(pl, genreHint: genreHint)
            let card = HomeCard(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: subtitle ?? "\(pl.tracks) tracks",
                artPath: resolvedArt,
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
            if let lib = HomeFeedRules.matchingLibraryGenreForLabel(genre, libraryGenres: input.libraryGenres),
               let path = lib.art_path?.trimmingCharacters(in: .whitespacesAndNewlines), !path.isEmpty,
               let p = registry.claimArtPath(path) { return p }
            if let p = registry.claimArtPath(HomeFeedRules.artPathForGenreDistinct(input.history, genre: genre, used: registry.usedArtPaths)) { return p }
            if let a = artist, let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, artist: a, used: registry.usedArtPaths)) { return p }
            if let a = topArtists[safe: index]?.name ?? topArtists[safe: index]?.label,
               let p = registry.claimArtPath(HomeFeedRules.artPathForArtistDistinct(input.history, artist: a, used: registry.usedArtPaths)) { return p }
            return nil
        }

        let moodSections = HomeMoodSections.all().compactMap { mood -> HomeSection? in
            let cards = buildMoodSectionCards(
                mood: mood,
                input: input,
                registry: &registry,
                playlistById: playlistById,
                topArtists: topArtists,
                resolveMixArt: resolveMixArt
            )
            return section("mood-\(mood.id)", mood.title, .mood, cards)
        }
        registry.reserveMoodPlaylists(moodSections.flatMap(\.cards))

        // MARK: Jump back in

        var jumpBackIn: [HomeCard] = []
        if let resume = input.continueResume, let path = resume.filepath {
            let pct = resume.durationMs > 0 ? Int((resume.offsetMs * 100) / resume.durationMs) : 0
            let card = HomeCard(
                id: "continue-\(path)",
                title: resume.track ?? "Continue listening",
                subtitle: "\(pct)% · \(resume.artist ?? resume.context?.name ?? "Pick up where you left off")",
                artPath: path,
                playlistId: resume.context?.id,
                playTarget: .song(path: path, title: resume.track ?? path),
                kind: .jumpBackIn
            )
            registry.registerCard(card)
            jumpBackIn.append(card)
        }
        for card in dashboardJumpCards(input.dashboard, playlistByName: playlistByName, artByPlaylist: artByPlaylist) {
            guard jumpBackIn.count < HomeFeedLimits.jumpBackIn else { break }
            guard !registry.hasCard(id: card.id) else { continue }
            registry.registerCard(card)
            jumpBackIn.append(card)
        }

        for name in recentPlaylistNames {
            guard jumpBackIn.count < HomeFeedLimits.jumpBackIn else { break }
            guard let pl = playlistByName[name.lowercased()] else { continue }
            if let card = playlistCard(pl, kind: .jumpBackIn, subtitle: "Recently played") {
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
               let card = playlistCard(pl, kind: .topMixes, subtitle: "\(genre) mix", genreHint: genre) {
                genreMixes.append(card)
                continue
            }
            if let named = HomeFeedRules.bestGenreMixPlaylist(input.allPlaylists, genre: genre) {
                // A real genre-mix playlist represents this genre — never synthesize a
                // "\(genre) Mix" artist card, even if it already appears elsewhere.
                if let card = playlistCard(named, kind: .topMixes, subtitle: "\(genre) mix", genreHint: genre) {
                    genreMixes.append(card)
                }
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
                playTarget: .radio(displayTitle: "\(genre) Mix", seedKind: .genre, name: seed, path: nil),
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
            HomeFeedRules.isGenreMixPlaylistName($0.name) || HomeFeedRules.hasMixLikeName($0.name)
        }.sorted { $0.tracks > $1.tracks }
        dailyMixes.append(contentsOf: fillPlaylists(
            from: mixLikePool + shuffledGeneric,
            target: HomeFeedLimits.dailyMixes - dailyMixes.count,
            kind: .dailyMixes,
            subtitle: { _ in "Mix playlist" }
        ))
        dailyMixes = dailyMixes.uniqued(by: \.id).prefix(HomeFeedLimits.dailyMixes).map { $0 }

        // MARK: Explore themes

        let exploreThemes = buildExploreThemeCards(
            input: input,
            registry: &registry,
            playlistById: playlistById,
            topArtists: topArtists,
            topGenres: topGenres,
            resolveMixArt: resolveMixArt
        )

        // MARK: Recent playlists

        var recentPlaylists: [HomeCard] = []
        for name in recentPlaylistNames {
            guard recentPlaylists.count < HomeFeedLimits.recentPlaylists else { break }
            guard let pl = playlistByName[name.lowercased()] else { continue }
            if let card = playlistCard(pl, kind: .recentPlaylists, subtitle: "Played recently") {
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

        // MARK: Release radar

        var releaseRadar: [HomeCard] = []
        if let label = input.releaseRadarLabel {
            let card = HomeCard(
                id: "release-radar",
                title: "Release Radar",
                subtitle: label,
                artPath: input.releaseRadarArtPath,
                playlistId: nil,
                playTarget: .radio(displayTitle: "New in library", seedKind: .genre, name: "Library", path: input.releaseRadarArtPath),
                kind: .discover
            )
            if !registry.hasCard(id: card.id) {
                registry.registerCard(card)
                releaseRadar.append(card)
            }
        }

        // MARK: More playlists

        // Catch-all so no library playlist is permanently hidden from home; the
        // daily shuffle seed rotates which ones lead, and any not shown elsewhere land here.
        let allRotated = HomeFeedRules.shuffledAllPlaylists(input.allPlaylists, seed: input.shuffleSeed)
        let morePlaylists = fillPlaylists(
            from: allRotated + input.allPlaylists.sorted { $0.tracks > $1.tracks },
            target: HomeFeedLimits.morePlaylists,
            kind: .recentPlaylists,
            subtitle: { pl in "\(pl.tracks) tracks · From your library" }
        )

        var sections: [HomeSection] = [
            section("jump-back-in", "Jump back in", .jumpBackIn, jumpBackIn),
            section("favorites", "Your favorites", .favorites, favoriteCards),
            section("top-mixes", "Your top mixes", .topMixes, genreMixes),
        ].compactMap { $0 }
        if let rr = section("release-radar", "Release Radar", .discover, releaseRadar) {
            sections.append(rr)
        }
        if !input.discoverWeeklyCards.isEmpty,
           let dw = section("discover-weekly", "Discover Weekly", .discover, input.discoverWeeklyCards) {
            sections.append(dw)
        }
        sections.append(contentsOf: moodSections)
        sections.append(contentsOf: [
            section("explore-themes", "Explore genres & worlds", .exploreThemes, exploreThemes),
            section("daily-mixes", "New daily mixes", .dailyMixes, dailyMixes),
            section("recent-playlists", "Recent playlists", .recentPlaylists, recentPlaylists),
            section("radio", "Radio", .radio, radioCards),
            section("discover", "Discover", .discover, discoverCandidates),
            section("more-playlists", "More playlists", .recentPlaylists, morePlaylists),
        ].compactMap { $0 })

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
               !HomeFeedRules.isAutomationPlaylistName(playlist),
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

    private static func buildExploreThemeCards(
        input: HomeFeedInput,
        registry: inout HomeFeedRegistry,
        playlistById: [String: PlaylistSummary],
        topArtists: [CountRow],
        topGenres: [CountRow],
        resolveMixArt: (String, String?, Int) -> String?
    ) -> [HomeCard] {
        var cards: [HomeCard] = []
        var coveredGenreKeys = Set<String>()
        let themes = HomeThemeCatalog.themesForDay(seed: input.shuffleSeed)

        func registerThemeCard(_ card: HomeCard) {
            guard !cards.contains(where: { $0.id == card.id }) else { return }
            if !registry.hasCard(id: card.id) {
                registry.registerCard(card)
            }
            cards.append(card)
        }

        func playlistCardForTheme(_ pl: PlaylistSummary, subtitle: String) -> HomeCard? {
            guard registry.claimPlaylist(id: pl.id, name: pl.name) else { return nil }
            return HomeCard(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: subtitle,
                artPath: nil,
                playlistId: pl.id,
                playTarget: .playlist(id: pl.id, name: pl.name),
                kind: .exploreThemes
            )
        }

        for (index, theme) in themes.enumerated() {
            guard cards.count < HomeFeedLimits.exploreThemes else { break }

            if let playlistMatch = input.allPlaylists
                .filter({ HomeFeedRules.playlistMatchesTheme($0, theme: theme) && $0.tracks > 0 })
                .max(by: { lhs, rhs in
                    let ls = HomeFeedRules.playlistThemeScore(lhs, theme: theme)
                    let rs = HomeFeedRules.playlistThemeScore(rhs, theme: theme)
                    if ls != rs { return ls < rs }
                    return lhs.tracks < rhs.tracks
                }),
               let card = playlistCardForTheme(playlistMatch, subtitle: theme.subtitle) {
                registerThemeCard(card)
                continue
            }

            if let smart = input.smartPlaylists.first(where: { $0.enabled && HomeFeedRules.playlistMatchesTheme($0.name, theme: theme) }),
               let pl = smart.playlistId.flatMap({ playlistById[$0] }),
               let card = playlistCardForTheme(pl, subtitle: theme.subtitle) {
                registerThemeCard(card)
                continue
            }

            let libraryGenre = HomeFeedRules.matchingLibraryGenre(theme, libraryGenres: input.libraryGenres)
            let analyticsGenre = topGenres.first { row in
                let name = row.name ?? row.label ?? ""
                return !name.isEmpty && HomeFeedRules.genreMatchesTheme(name, theme: theme)
            }.flatMap { $0.name ?? $0.label }
            let genreLabel = libraryGenre ?? analyticsGenre

            if let genreLabel {
                coveredGenreKeys.insert(genreLabel.lowercased())
                let libraryItem = input.libraryGenres.first { $0.name.caseInsensitiveCompare(genreLabel) == .orderedSame }
                let seedArtist = HomeFeedRules.topArtistForGenre(input.history, genre: genreLabel)
                    ?? HomeFeedRules.topArtistForTheme(input.history, theme: theme)
                    ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label
                    ?? topArtists.first?.name ?? topArtists.first?.label
                    ?? genreLabel
                let card = HomeCard(
                    id: "theme-\(theme.id)",
                    title: theme.title,
                    subtitle: theme.subtitle,
                    artPath: libraryItem?.art_path ?? resolveMixArt(genreLabel, seedArtist, index),
                    playlistId: nil,
                    playTarget: .artist(name: seedArtist),
                    kind: .exploreThemes
                )
                registerThemeCard(card)
                continue
            }

            if let seedArtist = HomeFeedRules.topArtistForTheme(input.history, theme: theme)
                ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label {
                let card = HomeCard(
                    id: "theme-\(theme.id)",
                    title: theme.title,
                    subtitle: theme.subtitle,
                    artPath: resolveMixArt(theme.title, seedArtist, index),
                    playlistId: nil,
                    playTarget: .artist(name: seedArtist),
                    kind: .exploreThemes
                )
                registerThemeCard(card)
                continue
            }

            let fallbackSeed = topArtists.first?.name ?? topArtists.first?.label
                ?? input.libraryGenres.first?.name
                ?? "Library"
            let card = HomeCard(
                id: "theme-\(theme.id)",
                title: theme.title,
                subtitle: theme.subtitle,
                artPath: resolveMixArt(theme.title, fallbackSeed, index),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(theme.title) Radio", seedKind: .genre, name: fallbackSeed, path: nil),
                kind: .exploreThemes
            )
            registerThemeCard(card)
        }

        for genre in input.libraryGenres {
            guard cards.count < HomeFeedLimits.exploreThemes + HomeFeedLimits.libraryGenreExtras else { break }
            guard genre.track_count >= 8 else { continue }
            let key = genre.name.lowercased()
            guard !coveredGenreKeys.contains(key) else { continue }
            guard !themes.contains(where: { HomeFeedRules.genreMatchesTheme(genre.name, theme: $0) }) else { continue }
            let cardId = "library-genre-\(genre.name)"
            guard !registry.hasCard(id: cardId) else { continue }
            let seedArtist = HomeFeedRules.topArtistForGenre(input.history, genre: genre.name)
                ?? topArtists.first?.name ?? topArtists.first?.label
                ?? genre.name
            let card = HomeCard(
                id: cardId,
                title: genre.name,
                subtitle: "\(genre.track_count) tracks · From your library",
                artPath: registry.claimArtPath(genre.art_path),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(genre.name) Radio", seedKind: .genre, name: seedArtist, path: nil),
                kind: .exploreThemes
            )
            coveredGenreKeys.insert(key)
            registerThemeCard(card)
        }

        return cards.uniqued(by: \.id)
    }

    private static func buildMoodSectionCards(
        mood: HomeMoodSection,
        input: HomeFeedInput,
        registry: inout HomeFeedRegistry,
        playlistById: [String: PlaylistSummary],
        topArtists: [CountRow],
        resolveMixArt: (String, String?, Int) -> String?
    ) -> [HomeCard] {
        let theme = mood.theme
        let kind = HomeSectionKind.mood
        let limit = HomeFeedLimits.moodSectionCards
        var cards: [HomeCard] = []

        func addCard(_ card: HomeCard?) {
            guard let card else { return }
            guard !registry.hasCard(id: card.id) else { return }
            guard !cards.contains(where: { $0.id == card.id }) else { return }
            registry.registerMoodCard(card)
            cards.append(card)
        }

        func moodPlaylistCard(_ pl: PlaylistSummary, subtitle: String) -> HomeCard? {
            guard pl.tracks > 0 else { return nil }
            let cardId = "mood-\(mood.id)-pl-\(pl.id)"
            guard !registry.hasCard(id: cardId) else { return nil }
            return HomeCard(
                id: cardId,
                title: pl.name,
                subtitle: subtitle,
                artPath: nil,
                playlistId: pl.id,
                playTarget: .playlist(id: pl.id, name: pl.name),
                kind: kind
            )
        }

        for pl in HomeFeedRules.playlistsForMoodSection(input.allPlaylists, theme: theme) {
            guard cards.count < limit else { break }
            addCard(moodPlaylistCard(pl, subtitle: theme.subtitle))
        }

        for sp in input.smartPlaylists where sp.enabled && HomeFeedRules.playlistMatchesMoodSection(sp.name, theme: theme) {
            guard cards.count < limit else { break }
            guard let pl = sp.playlistId.flatMap({ playlistById[$0] }) else { continue }
            addCard(moodPlaylistCard(pl, subtitle: theme.subtitle))
        }

        if cards.isEmpty {
            let seedArtist = HomeFeedRules.topArtistForTheme(input.history, theme: theme)
                ?? topArtists.first?.name ?? topArtists.first?.label ?? mood.title
            addCard(HomeCard(
                id: "mood-\(mood.id)-fallback",
                title: theme.title,
                subtitle: theme.subtitle,
                artPath: resolveMixArt(theme.title, seedArtist, mood.id.hashValue),
                playlistId: nil,
                playTarget: .radio(displayTitle: "\(mood.title) Radio", seedKind: .genre, name: seedArtist, path: nil),
                kind: kind
            ))
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
