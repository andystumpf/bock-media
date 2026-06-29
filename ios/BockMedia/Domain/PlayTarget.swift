import Foundation

enum PlayTarget: Equatable, Identifiable, Hashable {
    case playlist(id: String, name: String)
    case artist(name: String)
    case album(name: String, artist: String?)
    case song(path: String, title: String)
    case radio(displayTitle: String, seedKind: RadioSeedKind, name: String, path: String?)

    enum RadioSeedKind: Equatable {
        case artist, song, genre
    }

    var id: String {
        switch self {
        case .playlist(let id, _): return "pl-\(id)"
        case .artist(let name): return "artist-\(name)"
        case .album(let name, let artist): return "album-\(name)-\(artist ?? "")"
        case .song(let path, _): return "song-\(path)"
        case .radio(_, _, let name, let path): return "radio-\(name)-\(path ?? "")"
        }
    }

    var label: String {
        switch self {
        case .playlist(_, let name): return name
        case .artist(let name): return name
        case .album(let name, _): return name
        case .song(_, let title): return title
        case .radio(let displayTitle, _, _, _): return displayTitle
        }
    }

    var shuffleDefault: Bool {
        if case .radio = self { return true }
        return false
    }
}

func repositoryPlay(
    repository: BockMediaRepository,
    target: PlayTarget,
    device: String,
    shuffle: Bool
) async throws -> PlayResponse {
    switch target {
    case .playlist(let id, let name):
        var playlistId = id
        if playlistId.isEmpty, let resolved = await repository.resolvePlaylistId(name) {
            playlistId = resolved
        }
        return try await repository.playOnDevice(
            device: device, kind: "playlist", name: name,
            id: playlistId.isEmpty ? nil : playlistId, shuffle: shuffle
        )
    case .artist(let name):
        return try await repository.playOnDevice(device: device, kind: "artist", name: name, shuffle: shuffle)
    case .album(let name, let artist):
        return try await repository.playOnDevice(device: device, kind: "album", name: name, artist: artist, shuffle: shuffle)
    case .song(let path, let title):
        return try await repository.playOnDevice(device: device, kind: "song", name: title, path: path, shuffle: shuffle)
    case .radio(_, let seedKind, let name, let path):
        switch seedKind {
        case .artist, .genre:
            return try await repository.playOnDevice(device: device, kind: "artist", name: name, shuffle: shuffle)
        case .song:
            return try await repository.playOnDevice(device: device, kind: "song", name: name, path: path, shuffle: shuffle)
        }
    }
}
