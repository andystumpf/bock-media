import Foundation

extension PlayTarget {
    func downloadId() -> String {
        switch self {
        case .playlist(let id, _): return "pl-\(id)"
        case .artist(let name): return "artist-\(slug(name))"
        case .album(let name, let artist): return "album-\(slug(name))-\(slug(artist ?? ""))"
        case .song(let path, _): return "song-\(slug(path))"
        case .radio(_, let seed, let name, let path):
            switch seed {
            case .song: return "radio-song-\(slug(path ?? name))"
            case .artist: return "radio-artist-\(slug(name))"
            case .genre: return "radio-genre-\(slug(name))"
            }
        }
    }

    func downloadKindLabel() -> String {
        switch self {
        case .playlist: return "Playlist"
        case .artist: return "Artist"
        case .album: return "Album"
        case .song: return "Song"
        case .radio: return "Mix"
        }
    }
}

private func slug(_ raw: String) -> String {
    let trimmed = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
    return trimmed.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? trimmed
}
