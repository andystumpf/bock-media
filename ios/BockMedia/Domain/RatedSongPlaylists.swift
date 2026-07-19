import Foundation

/// Virtual playlists backed by GET /api/playlists/rated-stars-N
enum RatedSongPlaylists {
    static let starLevelsDescending = [5, 4, 3, 2, 1]

    static func id(_ stars: Int) -> String {
        "rated-stars-\(min(5, max(1, stars)))"
    }

    static func title(_ stars: Int) -> String {
        "\(min(5, max(1, stars)))★ songs"
    }

    static func playTarget(_ stars: Int) -> PlayTarget {
        let n = min(5, max(1, stars))
        return .playlist(id: id(n), name: title(n))
    }
}

enum RatingKind: String {
    case song, album, playlist, artist
}

struct RatingTarget {
    let kind: RatingKind
    let id: String
    let title: String
    var artist: String?
    var album: String?
}

func albumRatingId(album: String, artist: String?) -> String {
    "\(album.trimmingCharacters(in: .whitespacesAndNewlines))|\(artist?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "")"
}
