import Foundation

struct SearchBrowseFeed {
    var pickedForYou: [HomeCard] = []
    var newReleases: [AlbumItem] = []
    var genres: [GenreItem] = []
}

enum SearchSuggestionKind {
    case artist, album, playlist, song
}

struct SearchSuggestion: Identifiable {
    let kind: SearchSuggestionKind
    let title: String
    let subtitle: String?
    let hitId: String?
    let path: String?
    let artist: String?
    let album: String?

    var id: String {
        switch kind {
        case .artist: return "artist-\(title)"
        case .album: return "album-\(title)-\(artist ?? "")"
        case .playlist: return "pl-\(hitId ?? title)"
        case .song: return "song-\(path ?? title)"
        }
    }
}

enum SearchBrowseLoader {
    private static let historyLimit = 80

    static func load(repository: BockMediaRepository) async -> SearchBrowseFeed {
        async let historyTask = loadHistory(repository: repository)
        async let analyticsTask = analyticsWithTimeout(repository: repository)
        async let playlistsTask = loadPlaylists(repository: repository)
        async let smartTask = try? await repository.smartPlaylists()
        async let genresTask = try? await repository.genres(limit: 16)
        async let newReleasesTask = try? await repository.recentAlbums(limit: 12)
        async let dashboardTask = loadDashboard(repository: repository)

        let history = await historyTask?.items ?? []
        let analytics = await analyticsTask
        let allPlaylists = await playlistsTask?.items ?? []
        let smartPlaylists = await smartTask ?? []
        let genres = await genresTask ?? []
        let newReleases = await newReleasesTask?.items ?? []
        let dashboard = await dashboardTask

        let playlistByName = Dictionary(
            allPlaylists.map { ($0.name.lowercased(), $0) },
            uniquingKeysWith: { _, last in last }
        )
        let playlistById = Dictionary(
            allPlaylists.map { ($0.id, $0) },
            uniquingKeysWith: { _, last in last }
        )
        var usedArtPaths = Set<String>()

        func claimArt(_ path: String?) -> String? {
            guard let path, !path.isEmpty, !usedArtPaths.contains(path) else { return nil }
            usedArtPaths.insert(path)
            return path
        }

        func playlistCard(_ pl: PlaylistSummary, artPath: String?, subtitle: String) -> HomeCard {
            HomeCard(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: subtitle,
                artPath: artPath,
                playlistId: pl.id,
                playTarget: .playlist(id: pl.id, name: pl.name),
                kind: .discover
            )
        }

        var recentPlaylistNames: [String] = []
        var artByPlaylist: [String: String] = [:]
        for row in history {
            guard let name = row.playlist?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { continue }
            if !recentPlaylistNames.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) {
                recentPlaylistNames.append(name)
            }
            if let path = row.filepath {
                artByPlaylist[name.lowercased()] = artByPlaylist[name.lowercased()] ?? path
            }
        }

        let topGenres = Array(analytics?.topGenres.prefix(6) ?? [])
        let topArtists = analytics?.topArtists ?? []

        let genreMixes: [HomeCard] = topGenres.enumerated().compactMap { index, row in
            let genre = row.name ?? row.label ?? ""
            guard !genre.isEmpty else { return nil }
            let smart = smartPlaylists.first(where: { sp in
                !HomeFeedRules.isDailyMixName(sp.name) && sp.name.localizedCaseInsensitiveContains(genre)
            })
            let artPath = claimArt(HomeFeedRules.artPathForGenreDistinct(history, genre: genre, used: usedArtPaths))
                ?? claimArt(HomeFeedRules.artPathForArtistDistinct(
                    history,
                    artist: HomeFeedRules.topArtistForGenre(history, genre: genre)
                        ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label ?? "",
                    used: usedArtPaths
                ))
                ?? claimArt(HomeFeedRules.nextDistinctArtPath(history, used: usedArtPaths))

            if let linkedId = smart?.playlistId, let pl = playlistById[linkedId] {
                return playlistCard(pl, artPath: artPath, subtitle: "\(genre) mix")
            }
            if let named = allPlaylists.first(where: { HomeFeedRules.isGenreMixPlaylistName($0.name, genre: genre) }) {
                return playlistCard(named, artPath: artPath, subtitle: "\(genre) mix")
            }
            guard let seed = HomeFeedRules.topArtistForGenre(history, genre: genre)
                ?? topArtists[safe: index]?.name ?? topArtists[safe: index]?.label else { return nil }
            return HomeCard(
                id: "mix-\(genre)",
                title: "\(genre) Mix",
                subtitle: "Based on your listening",
                artPath: artPath,
                playlistId: nil,
                playTarget: .artist(name: seed),
                kind: .topMixes
            )
        }

        var recentAlbumCards: [HomeCard] = []
        var seenAlbums = Set<String>()
        for row in history {
            guard let album = row.album?.trimmingCharacters(in: .whitespaces), !album.isEmpty else { continue }
            let key = "\(album.lowercased())|\(row.artist?.lowercased() ?? "")"
            guard seenAlbums.insert(key).inserted else { continue }
            recentAlbumCards.append(HomeCard(
                id: "album-\(key)",
                title: album,
                subtitle: row.artist ?? "Recently played",
                artPath: claimArt(row.filepath),
                playlistId: nil,
                playTarget: .album(name: album, artist: row.artist),
                kind: .jumpBackIn
            ))
            if recentAlbumCards.count >= 4 { break }
        }

        let discoverCards = allPlaylists
            .filter { HomeFeedRules.isDiscoverName($0.name) }
            .sorted { parseSortDate($0.createDate) > parseSortDate($1.createDate) }
            .prefix(4)
            .map { pl in
                playlistCard(pl, artPath: claimArt(artByPlaylist[pl.name.lowercased()]), subtitle: "Discover")
            }

        let recentPlaylistCards = recentPlaylistNames.prefix(4).compactMap { name -> HomeCard? in
            guard let pl = playlistByName[name.lowercased()] else { return nil }
            return playlistCard(pl, artPath: claimArt(artByPlaylist[name.lowercased()]), subtitle: "Played recently")
        }

        let dashboardCards = dashboardRecentCards(
            dashboard,
            playlistByName: playlistByName,
            artByPlaylist: artByPlaylist,
            claimArt: claimArt
        )

        let pickedForYou = (dashboardCards + genreMixes + recentAlbumCards + discoverCards + recentPlaylistCards)
            .uniqued(by: \.id)
            .prefix(12)
            .map { $0 }

        return SearchBrowseFeed(pickedForYou: pickedForYou, newReleases: newReleases, genres: genres)
    }

    static func suggestOneChar(repository: BockMediaRepository, query: String) async -> [SearchSuggestion] {
        guard let artists = try? await repository.artists(page: 1, limit: 6, search: query) else { return [] }
        return artists.items.map { artist in
            SearchSuggestion(
                kind: .artist,
                title: artist.name,
                subtitle: "\(artist.tracks) songs",
                hitId: nil,
                path: nil,
                artist: nil,
                album: nil
            )
        }
    }

    static func suggestionsFromResponse(_ response: SearchResponse) -> [SearchSuggestion] {
        var out: [SearchSuggestion] = []
        for hit in response.artists.prefix(2) {
            guard let title = hit.displayName, !title.isEmpty else { continue }
            out.append(SearchSuggestion(kind: .artist, title: title, subtitle: "Artist", hitId: hit.id, path: hit.path, artist: hit.artist, album: nil))
        }
        for hit in response.albums.prefix(2) {
            guard let title = hit.displayName, !title.isEmpty else { continue }
            out.append(SearchSuggestion(kind: .album, title: title, subtitle: hit.artist, hitId: hit.id, path: hit.path, artist: hit.artist, album: hit.album))
        }
        for hit in response.playlists.prefix(2) {
            out.append(SearchSuggestion(kind: .playlist, title: hit.name ?? "", subtitle: "Playlist", hitId: hit.id, path: hit.path, artist: nil, album: nil))
        }
        for hit in response.songs.prefix(2) {
            out.append(SearchSuggestion(kind: .song, title: hit.title ?? hit.name ?? "", subtitle: hit.artist, hitId: hit.id, path: hit.path, artist: hit.artist, album: hit.album))
        }
        return Array(out.prefix(8))
    }

    static func playTarget(for suggestion: SearchSuggestion) -> PlayTarget? {
        switch suggestion.kind {
        case .artist: return .artist(name: suggestion.title)
        case .album: return .album(name: suggestion.title, artist: suggestion.artist)
        case .playlist:
            guard let id = suggestion.hitId else { return nil }
            return .playlist(id: id, name: suggestion.title)
        case .song:
            guard let path = suggestion.path else { return nil }
            return .song(path: path, title: suggestion.title)
        }
    }

    private static func loadHistory(repository: BockMediaRepository) async -> StreamHistoryResponse? {
        if let cached = SessionDataStore.peekHistory() { return cached }
        return try? await repository.streamHistory(limit: historyLimit)
    }

    private static func loadPlaylists(repository: BockMediaRepository) async -> PlaylistsResponse? {
        let memberKey = ActiveProfileStore.activeMemberId() ?? ""
        if let cached = SessionDataStore.peekPlaylists(memberKey: memberKey) { return cached }
        return try? await repository.playlists(limit: 200)
    }

    private static func loadDashboard(repository: BockMediaRepository) async -> DashboardQuickResponse? {
        if let cached = SessionDataStore.peekDashboard() { return cached }
        return try? await repository.dashboardQuick()
    }

    private static func analyticsWithTimeout(repository: BockMediaRepository) async -> AnalyticsResponse? {
        if let cached = SessionDataStore.peekAnalytics() { return cached }
        return await withTaskGroup(of: AnalyticsResponse?.self) { group in
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

    private static func dashboardRecentCards(
        _ dashboard: DashboardQuickResponse?,
        playlistByName: [String: PlaylistSummary],
        artByPlaylist: [String: String],
        claimArt: (String?) -> String?
    ) -> [HomeCard] {
        guard let dashboard else { return [] }
        return dashboard.recent.compactMap { item -> HomeCard? in
            if let playlistName = item.playlist?.trimmingCharacters(in: .whitespaces), !playlistName.isEmpty,
               let pl = playlistByName[playlistName.lowercased()] {
                return HomeCard(
                    id: "dash-pl-\(pl.id)",
                    title: pl.name,
                    subtitle: "Recently played",
                    artPath: claimArt(artByPlaylist[playlistName.lowercased()] ?? item.path),
                    playlistId: pl.id,
                    playTarget: .playlist(id: pl.id, name: pl.name),
                    kind: .discover
                )
            }
            guard let path = item.path, let title = item.track?.trimmingCharacters(in: .whitespaces), !title.isEmpty else { return nil }
            return HomeCard(
                id: "dash-\(path)",
                title: title,
                subtitle: item.artist ?? "Recently played",
                artPath: claimArt(path),
                playlistId: nil,
                playTarget: .song(path: path, title: title),
                kind: .discover
            )
        }
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
