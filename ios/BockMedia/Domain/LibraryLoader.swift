import Foundation

enum LibraryFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case playlists = "Playlists"
    case artists = "Artists"
    case albums = "Albums"
    case downloaded = "Downloaded"

    var id: String { rawValue }
    var label: String { rawValue }
}

enum LibraryViewMode { case list, grid }

enum LibrarySort { case recents, name }

enum LibraryItemKind { case playlist, artist, album, downloaded }

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

    static func search(
        repository: BockMediaRepository,
        filter: LibraryFilter,
        query: String
    ) async -> [LibraryItem] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        switch filter {
        case .playlists, .all:
            return await loadPlaylists(repository: repository, search: q, limit: searchLimit)
        case .artists:
            return await loadArtists(repository: repository, search: q, limit: searchLimit)
        case .albums:
            return await loadAlbums(repository: repository, search: q, limit: searchLimit)
        case .downloaded:
            await OfflineDownloadManager.shared.refresh()
            return OfflineDownloadStore().listManifests()
                .filter { $0.title.localizedCaseInsensitiveContains(q) }
                .map(offlineItem(manifest:))
        }
    }

    private static func loadPlaylists(repository: BockMediaRepository, search: String, limit: Int) async -> [LibraryItem] {
        let items = (try? await repository.playlists(search: search, limit: limit))?.items ?? []
        await repository.prefetchPlaylistCoverPaths(ids: items.map(\.id))
        return items.map { pl in
            LibraryItem(
                id: "pl-\(pl.id)",
                title: pl.name,
                subtitle: "\(pl.tracks) songs",
                kind: .playlist,
                artPath: pl.artPath,
                playTarget: .playlist(id: pl.id, name: pl.name),
                playlistId: pl.id
            )
        }
    }

    private static func loadArtists(repository: BockMediaRepository, search: String, limit: Int) async -> [LibraryItem] {
        (try? await repository.artists(page: 1, limit: limit, search: search))?.items.map { artist in
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

    private static func loadAlbums(repository: BockMediaRepository, search: String, limit: Int) async -> [LibraryItem] {
        (try? await repository.albums(page: 1, limit: limit, search: search))?.items.map { album in
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
