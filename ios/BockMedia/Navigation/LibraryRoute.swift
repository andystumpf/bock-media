import Foundation

enum LibraryRoute: Hashable {
    case favorites
    case playlists
    case playlistDetail(String)
    case artists
    case albums
    case songs
    case managePlaylists
}
