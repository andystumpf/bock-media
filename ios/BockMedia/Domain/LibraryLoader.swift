import Foundation

enum LibraryFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case playlists = "Playlists"
    case artists = "Artists"
    case albums = "Albums"
    case tracks = "Tracks"
    case downloaded = "Downloaded"

    var id: String { rawValue }
    var label: String { rawValue }
}

enum LibraryViewMode { case list, grid }

enum LibrarySort { case recents, name }

enum LibraryItemKind { case playlist, artist, album, track, downloaded }

struct LibraryItem: Identifiable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let kind: LibraryItemKind
    let playTarget: PlayTarget
    let artPath: String?
    let playlistId: String?
    let artistName: String?
    let albumName: String?
    let sortDate: Int64
    let unplayed: Bool

    init(
        id: String,
        title: String,
        subtitle: String,
        kind: LibraryItemKind,
        playTarget: PlayTarget,
        artPath: String? = nil,
        playlistId: String? = nil,
        artistName: String? = nil,
        albumName: String? = nil,
        sortDate: Int64 = 0,
        unplayed: Bool = false
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.kind = kind
        self.playTarget = playTarget
        self.artPath = artPath
        self.playlistId = playlistId
        self.artistName = artistName
        self.albumName = albumName
        self.sortDate = sortDate
        self.unplayed = unplayed
    }
}

struct LibraryData {
    let playlists: [LibraryItem]
    let artists: [LibraryItem]
    let albums: [LibraryItem]
    let offline: [LibraryItem]

    func forFilter(_ filter: LibraryFilter) -> [LibraryItem] {
        switch filter {
        case .all: return playlists + artists + albums + offline
        case .playlists: return playlists
        case .artists: return artists
        case .albums: return albums
        case .tracks: return []
        case .downloaded: return offline
        }
    }
}

enum LibraryLoader {
    private static let browsePlaylistLimit = 80
    private static let browseArtistLimit = 60
    private static let browseAlbumLimit = 60
    private static let searchLimit = 100

    static func loadBuckets(repository: BockMediaRepository) async -> LibraryData {
        async let playlistsTask = loadPlaylists(repository: repository, search: "", limit: browsePlaylistLimit)
        async let artistsTask = loadArtists(repository: repository, search: "", limit: browseArtistLimit)
        async let albumsTask = loadAlbums(repository: repository, search: "", limit: browseAlbumLimit)
        async let offlineTask = loadOffline()

        return LibraryData(
            playlists: await playlistsTask,
            artists: await artistsTask,
            albums: await albumsTask,
            offline: await offlineTask
        )
    }

    static func unifiedSearch(repository: BockMediaRepository, query: String, source: String? = nil) async throws -> SearchResponse {
        try await UnifiedSearchCoordinator.fetch(repository: repository, query: query, source: source)
    }

    static func search(
        repository: BockMediaRepository,
        filter: LibraryFilter,
        query: String
    ) async -> [LibraryItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        switch filter {
        case .playlists:
            return await loadPlaylists(repository: repository, search: q, limit: searchLimit)
        case .all:
            return []
        case .artists:
            return await loadArtists(repository: repository, search: q, limit: searchLimit, page: 1)
        case .albums:
            return await loadAlbums(repository: repository, search: q, limit: searchLimit, page: 1)
        case .tracks:
            return await loadTracks(repository: repository, search: q, limit: searchLimit, page: 1)
        case .downloaded:
            await OfflineDownloadManager.shared.refresh()
            return OfflineDownloadStore().listManifests()
                .filter { $0.title.localizedCaseInsensitiveContains(q) }
                .map(offlineItem(manifest:))
        }
    }

    static func loadPage(
        repository: BockMediaRepository,
        filter: LibraryFilter,
        search: String,
        page: Int,
        limit: Int = 60
    ) async -> (items: [LibraryItem], total: Int) {
        switch filter {
        case .artists:
            let resp = try? await repository.artists(page: page, limit: limit, search: search)
            let items = resp?.items.map { artist in
                LibraryItem(
                    id: "ar-\(artist.name.hashValue)",
                    title: artist.name,
                    subtitle: "\(artist.albums) albums · \(artist.tracks) songs",
                    kind: .artist,
                    playTarget: .artist(name: artist.name),
                    artPath: artist.artPath,
                    artistName: artist.name
                )
            } ?? []
            return (items, resp?.total ?? items.count)
        case .albums:
            let resp = try? await repository.albums(page: page, limit: limit, search: search)
            let items = resp?.items.map { album in
                LibraryItem(
                    id: "al-\(album.name.hashValue)-\((album.artist ?? "").hashValue)",
                    title: album.name,
                    subtitle: [album.artist, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "),
                    kind: .album,
                    playTarget: .album(name: album.name, artist: album.artist),
                    artPath: album.artPath,
                    artistName: album.artist,
                    albumName: album.name,
                    unplayed: album.unplayed
                )
            } ?? []
            return (items, resp?.total ?? items.count)
        case .tracks:
            let resp = try? await repository.songs(page: page, limit: limit, search: search)
            let items = await loadTracks(repository: repository, search: search, limit: limit, page: page)
            return (items, resp?.total ?? items.count)
        default:
            return ([], 0)
        }
    }

    private static func loadPlaylists(repository: BockMediaRepository, search: String, limit: Int) async -> [LibraryItem] {
        let items = (try? await repository.playlists(search: search, limit: limit, memberScoped: true))?.items ?? []
        await repository.prefetchPlaylistCoverPaths(ids: items.map(\.id))
        return items.map { pl in
            LibraryItem(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: "\(pl.tracks) songs",
                kind: .playlist,
                playTarget: .playlist(id: pl.id, name: pl.name),
                artPath: pl.artPath,
                playlistId: pl.id
            )
        }
    }

    private static func loadArtists(repository: BockMediaRepository, search: String, limit: Int, page: Int = 1) async -> [LibraryItem] {
        (try? await repository.artists(page: page, limit: limit, search: search))?.items.map { artist in
            LibraryItem(
                id: "ar-\(artist.name.hashValue)",
                title: artist.name,
                subtitle: "\(artist.albums) albums · \(artist.tracks) songs",
                kind: .artist,
                playTarget: .artist(name: artist.name),
                artPath: artist.artPath,
                artistName: artist.name
            )
        } ?? []
    }

    private static func loadAlbums(repository: BockMediaRepository, search: String, limit: Int, page: Int = 1) async -> [LibraryItem] {
        (try? await repository.albums(page: page, limit: limit, search: search))?.items.map { album in
            LibraryItem(
                id: "al-\(album.name.hashValue)-\((album.artist ?? "").hashValue)",
                title: album.name,
                subtitle: [album.artist, album.year.map(String.init)].compactMap { $0 }.joined(separator: " · "),
                kind: .album,
                playTarget: .album(name: album.name, artist: album.artist),
                artPath: album.artPath,
                artistName: album.artist,
                albumName: album.name,
                unplayed: album.unplayed
            )
        } ?? []
    }

    static func loadTracks(repository: BockMediaRepository, search: String, limit: Int, page: Int = 1) async -> [LibraryItem] {
        (try? await repository.songs(page: page, limit: limit, search: search))?.items.compactMap { song in
            guard let path = song.path?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank,
                  let title = song.title?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank else {
                return nil
            }
            let subtitle = [song.artist, song.album].compactMap { $0?.nilIfBlank }.joined(separator: " · ")
            return LibraryItem(
                id: "tr-\(path)",
                title: title,
                subtitle: subtitle.isEmpty ? "Track" : subtitle,
                kind: .track,
                playTarget: .song(path: path, title: title),
                artPath: path,
                artistName: song.artist,
                albumName: song.album
            )
        } ?? []
    }

    private static func loadOffline() async -> [LibraryItem] {
        await OfflineDownloadManager.shared.refresh()
        return OfflineDownloadStore().listManifests()
            .filter { OfflineDownloadStore().isCollectionComplete($0) }
            .map(offlineItem(manifest:))
    }

    private static func offlineItem(manifest: OfflineCollectionManifest) -> LibraryItem {
        LibraryItem(
            id: "dl-\(manifest.id)",
            title: manifest.title,
            subtitle: "\(manifest.tracks.count) tracks · Offline",
            kind: .downloaded,
            playTarget: manifest.toPlayTarget(),
            artPath: manifest.coverArtPath,
            playlistId: manifest.sourcePlaylistId ?? manifest.legacyPlaylistId,
            sortDate: manifest.lastSyncedAtMs > 0 ? manifest.lastSyncedAtMs : manifest.downloadedAtMs
        )
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
