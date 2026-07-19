import Foundation

struct HomeDecadeSection: Equatable {
    let id: String
    let title: String
}

/// Fixed home rows — one section per decade, all matching library playlists.
enum HomeDecadeSections {
    static let sixties = HomeDecadeSection(id: "60s", title: "60s")
    static let seventies = HomeDecadeSection(id: "70s", title: "70s")
    static let eighties = HomeDecadeSection(id: "80s", title: "80s")
    static let nineties = HomeDecadeSection(id: "90s", title: "90s")

    static func all() -> [HomeDecadeSection] {
        [sixties, seventies, eighties, nineties]
    }
}
