import Foundation

enum SearchRoute: Hashable {
    case genre(String)
    case artist(String)
    case album(name: String, artist: String?)
    case releaseRadar
    case sonicAdventure
}
