import Foundation

enum LocalPlaybackQueueResolver {
    static func resolve(
        repository: BockMediaRepository,
        target: PlayTarget,
        maxTracks: Int = 150
    ) async throws -> [LocalTrack] {
        let base = try await repository.resolveBaseURL()
        let offline = OfflineDownloadStore()

        func track(path: String, title: String, artist: String?, album: String?) -> LocalTrack? {
            guard let urlString = ServerURL.streamURL(base: base, filepath: path),
                  let url = URL(string: urlString) else { return nil }
            return LocalTrack(
                path: path,
                title: title,
                artist: artist,
                album: album,
                streamURL: url,
                localFileURL: offline.localFileURL(path: path)
            )
        }

        switch target {
        case .song(let path, let title):
            guard let t = track(path: path, title: title, artist: nil, album: nil) else {
                throw LocalPlaybackError.missingStreamURL
            }
            return [t]

        case .playlist(let id, let name):
            var playlistId = id
            if playlistId.isEmpty, let resolved = await repository.resolvePlaylistId(name) {
                playlistId = resolved
            }
            guard !playlistId.isEmpty else { throw LocalPlaybackError.noTracks }
            var collected: [LocalTrack] = []
            var page = 1
            while collected.count < maxTracks {
                let detail = try await repository.playlistDetail(id: playlistId, page: page, limit: 200, sortBy: "original")
                for item in detail.tracks {
                    guard let path = item.path, !path.isEmpty else { continue }
                    guard let t = track(path: path, title: item.title ?? name, artist: item.artist, album: item.album) else {
                        continue
                    }
                    collected.append(t)
                    if collected.count >= maxTracks { return collected }
                }
                if detail.tracks.isEmpty || collected.count >= detail.total { break }
                page += 1
            }
            guard !collected.isEmpty else { throw LocalPlaybackError.noTracks }
            return collected

        case .artist(let name):
            return try await loadSongPages(repository: repository, maxTracks: maxTracks) { page in
                try await repository.songs(page: page, limit: 200, artist: name)
            } map: { song, _ in
                guard let path = song.path, !path.isEmpty else { return nil }
                return track(path: path, title: song.title ?? name, artist: song.artist, album: song.album)
            }

        case .album(let name, let artist):
            return try await loadSongPages(repository: repository, maxTracks: maxTracks) { page in
                try await repository.songs(page: page, limit: 200, artist: artist, album: name)
            } map: { song, _ in
                guard let path = song.path, !path.isEmpty else { return nil }
                return track(path: path, title: song.title ?? name, artist: song.artist, album: song.album)
            }

        case .radio(_, let seedKind, let name, let path):
            switch seedKind {
            case .song:
                if let path, let t = track(path: path, title: name, artist: nil, album: nil) {
                    return [t]
                }
                fallthrough
            case .artist, .genre:
                return try await loadSongPages(repository: repository, maxTracks: min(50, maxTracks)) { page in
                    try await repository.songs(page: page, limit: 200, search: name, artist: name)
                } map: { song, _ in
                    guard let p = song.path, !p.isEmpty else { return nil }
                    return track(path: p, title: song.title ?? name, artist: song.artist, album: song.album)
                }
            }
        }
    }

    private static func loadSongPages(
        repository: BockMediaRepository,
        maxTracks: Int,
        fetch: (Int) async throws -> SongsResponse,
        map: (SongItem, Int) -> LocalTrack?
    ) async throws -> [LocalTrack] {
        var collected: [LocalTrack] = []
        var page = 1
        while collected.count < maxTracks {
            let resp = try await fetch(page)
            for song in resp.items {
                guard let t = map(song, page) else { continue }
                collected.append(t)
                if collected.count >= maxTracks { return collected }
            }
            if resp.items.isEmpty || collected.count >= resp.total { break }
            page += 1
        }
        guard !collected.isEmpty else { throw LocalPlaybackError.noTracks }
        return collected
    }
}
