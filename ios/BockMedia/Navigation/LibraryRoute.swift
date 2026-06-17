import Foundation

enum LibraryRoute: Hashable {
    case favorites
    case playlists
    case playlistDetail(String)
    case artists
    case albums(artist: String?)
    case songs(artist: String?, album: String?)
    case managePlaylists
}
