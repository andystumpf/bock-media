import Foundation

/// Where a home section tile should navigate on tap (browse — not play).
enum HomeCardBrowse {
    enum Destination: Equatable {
        case playlist(String)
        case artist(String)
        case album(name: String, artist: String?)
        case genre(String)
        case releaseRadar
        case downloads
        case search
    }

    static func destination(for card: HomeCard) -> Destination? {
        if card.id == "release-radar" { return .releaseRadar }
        if card.kind == .offline { return .downloads }
        // Playlist id first: tile rotation swaps stale genre tiles for playlist
        // cards that keep kind=browseGenres — those must open playlist detail,
        // not a genre page named after the playlist (which lists nothing).
        if let id = card.linkedPlaylistId { return .playlist(id) }
        if card.kind == .browseGenres { return .genre(card.title) }
        if let genre = mixGenre(for: card) { return .genre(genre) }
        switch card.playTarget {
        case .playlist(let id, _):
            return .playlist(id)
        case .artist(let name):
            return .artist(name)
        case .album(let name, let artist):
            return .album(name: name, artist: artist)
        case .radio(let displayTitle, let seedKind, let name, _):
            switch seedKind {
            case .artist:
                return .artist(name)
            case .genre:
                return .genre(radioGenreLabel(displayTitle: displayTitle, seedName: name))
            case .song:
                return .search
            }
        case .song:
            return .search
        }
    }

    /// Genre behind a synthetic "<genre> Mix" tile (artist-seeded, no real playlist).
    private static func mixGenre(for card: HomeCard) -> String? {
        guard card.linkedPlaylistId == nil else { return nil }
        switch card.playTarget {
        case .artist:
            guard card.kind == .topMixes else { return nil }
            return HomeFeedRules.mixGenreLabel(card.title)
        case .radio(_, let seedKind, _, _):
            guard card.kind == .topMixes, seedKind == .genre else { return nil }
            return HomeFeedRules.mixGenreLabel(card.title)
        default:
            return nil
        }
    }

    private static func radioGenreLabel(displayTitle: String, seedName: String) -> String {
        HomeFeedRules.mixGenreLabel(displayTitle)
            ?? HomeFeedRules.genreRadioLabel(displayTitle)
            ?? seedName
    }
}

extension HomeCardBrowse.Destination {
    var libraryRoute: LibraryRoute? {
        switch self {
        case .playlist(let id):
            return .playlistDetail(id)
        case .artist(let name):
            return .albums(artist: name)
        case .album(let name, let artist):
            return .songs(artist: artist, album: name)
        case .genre, .releaseRadar, .downloads, .search:
            return nil
        }
    }

    var searchRoute: SearchRoute? {
        switch self {
        case .genre(let name):
            return .genre(name)
        case .releaseRadar:
            return .releaseRadar
        case .artist(let name):
            return .artist(name)
        case .album(let name, let artist):
            return .album(name: name, artist: artist)
        case .playlist, .downloads, .search:
            return nil
        }
    }
}
