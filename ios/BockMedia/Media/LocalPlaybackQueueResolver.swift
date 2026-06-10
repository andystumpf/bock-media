import Foundation

enum LocalPlaybackQueueResolver {
    static func resolve(
        repository: BockMediaRepository,
        target: PlayTarget,
        maxTracks: Int = 150
    ) async throws -> [LocalTrack] {
        switch target {
        case .song(let path, let title):
            return try await [makeTrack(repository: repository, path: path, title: title, artist: nil, album: nil)]

        case .playlist(let id, let name):
            var playlistId = id
            if playlistId.isEmpty, let resolved = await repository.resolvePlaylistId(name) {
                playlistId = resolved
            }
            guard !playlistId.isEmpty else { throw LocalPlaybackError.noTracks }
            let detail = try await repository.playlistDetail(id: playlistId)
            return try await Array(detail.tracks.prefix(maxTracks)).asyncCompactMap { track in
                guard let path = track.path, !path.isEmpty else { return nil }
                return try await makeTrack(
                    repository: repository,
                    path: path,
                    title: track.title ?? name,
                    artist: track.artist,
                    album: track.album
                )
            }

        case .artist(let name):
            let songs = try await repository.songs(limit: maxTracks, artist: name)
            return try await songs.items.asyncCompactMap { song in
                guard let path = song.path, !path.isEmpty else { return nil }
                return try await makeTrack(
                    repository: repository,
                    path: path,
                    title: song.title ?? name,
                    artist: song.artist,
                    album: song.album
                )
            }

        case .album(let name, let artist):
            let songs = try await repository.songs(limit: maxTracks, artist: artist, album: name)
            return try await songs.items.asyncCompactMap { song in
                guard let path = song.path, !path.isEmpty else { return nil }
                return try await makeTrack(
                    repository: repository,
                    path: path,
                    title: song.title ?? name,
                    artist: song.artist,
                    album: song.album
                )
            }

        case .radio(_, let seedKind, let name, let path):
            switch seedKind {
            case .song:
                if let path {
                    return try await [makeTrack(repository: repository, path: path, title: name, artist: nil, album: nil)]
                }
                fallthrough
            case .artist, .genre:
                let songs = try await repository.songs(limit: min(50, maxTracks), search: name, artist: name)
                return try await songs.items.asyncCompactMap { song in
                    guard let p = song.path, !p.isEmpty else { return nil }
                    return try await makeTrack(
                        repository: repository,
                        path: p,
                        title: song.title ?? name,
                        artist: song.artist,
                        album: song.album
                    )
                }
            }
        }
    }

    private static func makeTrack(
        repository: BockMediaRepository,
        path: String,
        title: String,
        artist: String?,
        album: String?
    ) async throws -> LocalTrack {
        guard let urlString = await repository.streamURL(for: path), let url = URL(string: urlString) else {
            throw LocalPlaybackError.missingStreamURL
        }
        let offline = OfflineDownloadStore().localFileURL(path: path)
        return LocalTrack(path: path, title: title, artist: artist, album: album, streamURL: url, localFileURL: offline)
    }
}

private extension Array {
    func asyncCompactMap<T>(_ transform: (Element) async throws -> T?) async rethrows -> [T] {
        var result: [T] = []
        for element in self {
            if let value = try await transform(element) {
                result.append(value)
            }
        }
        return result
    }
}
