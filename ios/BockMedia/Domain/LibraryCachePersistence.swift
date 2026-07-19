import Foundation

enum LibraryCachePersistence {
    private static let fileName = "library_cache.json"
    private static let maxAgeMs: Int64 = 24 * 60 * 60 * 1000

    private struct PlayTargetDto: Codable {
        let type: String
        let id: String?
        let name: String?
        let artist: String?
        let path: String?
        let title: String?
        let displayTitle: String?
        let seedKind: String?
    }

    private struct ItemDto: Codable {
        let id: String
        let title: String
        let subtitle: String
        let kind: String
        let artPath: String?
        let playlistId: String?
        let artistName: String?
        let albumName: String?
        let sortDate: Int64
        let playTarget: PlayTargetDto
    }

    private struct SnapshotDto: Codable {
        let savedAtMs: Int64
        let playlists: [ItemDto]
        let artists: [ItemDto]
        let albums: [ItemDto]
        let offline: [ItemDto]
    }

    static func save(_ data: LibraryData) {
        if data.playlists.isEmpty && data.artists.isEmpty && data.albums.isEmpty { return }
        let dto = SnapshotDto(
            savedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            playlists: data.playlists.map(itemToDto),
            artists: data.artists.map(itemToDto),
            albums: data.albums.map(itemToDto),
            offline: data.offline.map(itemToDto)
        )
        guard let url = cacheURL(), let json = try? JSONEncoder().encode(dto) else { return }
        try? json.write(to: url, options: .atomic)
    }

    static func load() -> LibraryData? {
        guard let url = cacheURL(),
              let data = try? Data(contentsOf: url),
              let dto = try? JSONDecoder().decode(SnapshotDto.self, from: data) else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if now - dto.savedAtMs > maxAgeMs { return nil }
        let library = LibraryData(
            playlists: dto.playlists.compactMap(dtoToItem),
            artists: dto.artists.compactMap(dtoToItem),
            albums: dto.albums.compactMap(dtoToItem),
            offline: dto.offline.compactMap(dtoToItem)
        )
        guard !library.playlists.isEmpty || !library.artists.isEmpty || !library.albums.isEmpty else { return nil }
        return library
    }

    static func clear() {
        guard let url = cacheURL() else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func cacheURL() -> URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent(fileName)
    }

    private static func itemToDto(_ item: LibraryItem) -> ItemDto {
        ItemDto(
            id: item.id,
            title: item.title,
            subtitle: item.subtitle,
            kind: kindName(item.kind),
            artPath: item.artPath,
            playlistId: item.playlistId,
            artistName: item.artistName,
            albumName: item.albumName,
            sortDate: item.sortDate,
            playTarget: targetToDto(item.playTarget)
        )
    }

    private static func dtoToItem(_ dto: ItemDto) -> LibraryItem? {
        guard let kind = parseKind(dto.kind), let target = dtoToTarget(dto.playTarget) else { return nil }
        return LibraryItem(
            id: dto.id,
            title: dto.title,
            subtitle: dto.subtitle,
            kind: kind,
            playTarget: target,
            artPath: dto.artPath,
            playlistId: dto.playlistId,
            artistName: dto.artistName,
            albumName: dto.albumName,
            sortDate: dto.sortDate
        )
    }

    private static func kindName(_ kind: LibraryItemKind) -> String {
        switch kind {
        case .playlist: return "playlist"
        case .artist: return "artist"
        case .album: return "album"
        case .track: return "track"
        case .downloaded: return "downloaded"
        }
    }

    private static func parseKind(_ raw: String) -> LibraryItemKind? {
        switch raw.lowercased() {
        case "playlist": return .playlist
        case "artist": return .artist
        case "album": return .album
        case "track": return .track
        case "downloaded": return .downloaded
        default: return nil
        }
    }

    private static func targetToDto(_ target: PlayTarget) -> PlayTargetDto {
        switch target {
        case .playlist(let id, let name):
            return PlayTargetDto(type: "playlist", id: id, name: name, artist: nil, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .artist(let name):
            return PlayTargetDto(type: "artist", id: nil, name: name, artist: nil, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .album(let name, let artist):
            return PlayTargetDto(type: "album", id: nil, name: name, artist: artist, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .song(let path, let title):
            return PlayTargetDto(type: "song", id: nil, name: nil, artist: nil, path: path, title: title, displayTitle: nil, seedKind: nil)
        case .radio(let displayTitle, let seedKind, let name, let path):
            let seed = switch seedKind {
            case .artist: "Artist"
            case .song: "Song"
            case .genre: "Genre"
            }
            return PlayTargetDto(type: "radio", id: nil, name: name, artist: nil, path: path, title: nil, displayTitle: displayTitle, seedKind: seed)
        }
    }

    private static func dtoToTarget(_ dto: PlayTargetDto) -> PlayTarget? {
        switch dto.type {
        case "playlist":
            return .playlist(id: dto.id ?? "", name: dto.name ?? "")
        case "artist":
            guard let name = dto.name else { return nil }
            return .artist(name: name)
        case "album":
            guard let name = dto.name else { return nil }
            return .album(name: name, artist: dto.artist)
        case "song":
            guard let path = dto.path else { return nil }
            return .song(path: path, title: dto.title ?? dto.name ?? "Track")
        case "radio":
            let seed: PlayTarget.RadioSeedKind = switch dto.seedKind?.lowercased() {
            case "song": .song
            case "genre": .genre
            default: .artist
            }
            return .radio(displayTitle: dto.displayTitle ?? dto.name ?? "", seedKind: seed, name: dto.name ?? "", path: dto.path)
        default:
            return nil
        }
    }
}
