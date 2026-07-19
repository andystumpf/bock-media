import Foundation

struct HomeSectionPin: Equatable {
    let sectionId: String
    let playlistId: String
    let playlistName: String
    let pinnedAtMs: Int64
}

enum HomeSectionPinsApplier {
    static func mergePins(server: [HomeSectionPin], local: [HomeSectionPin]) -> [HomeSectionPin] {
        guard !server.isEmpty else { return local }
        var seen = Set(server.map { "\($0.sectionId)\0\($0.playlistId)" })
        var out = server
        for pin in local {
            let key = "\(pin.sectionId)\0\(pin.playlistId)"
            if seen.insert(key).inserted { out.append(pin) }
        }
        return out
    }

    static func apply(_ feed: HomeFeed, pins: [HomeSectionPin], playlists: [PlaylistSummary]) -> HomeFeed {
        guard !pins.isEmpty else { return feed }
        let playlistById = Dictionary(playlists.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let bySection = Dictionary(grouping: pins, by: \.sectionId)
        let sections = feed.sections.map { section -> HomeSection in
            let sectionPins = (bySection[section.id] ?? []).sorted { $0.pinnedAtMs > $1.pinnedAtMs }
            guard !sectionPins.isEmpty else { return section }
            let pinCards: [HomeCard] = sectionPins.compactMap { pin in
                let pl = playlistById[pin.playlistId] ?? PlaylistSummary(
                    id: pin.playlistId,
                    name: pin.playlistName,
                    trackCount: 0
                )
                return HomeCard(
                    id: "pl-\(pl.id)",
                    title: pl.name,
                    subtitle: pl.trackCount > 0 ? "\(pl.trackCount) tracks · Pinned" : "Pinned for you",
                    artPath: pl.artPath,
                    playlistId: pl.id,
                    playTarget: .playlist(id: pl.id, name: pl.name),
                    kind: section.kind
                )
            }
            guard !pinCards.isEmpty else { return section }
            let pinIds = Set(pinCards.map(\.playlistId))
            let rest = section.cards.filter { card in
                guard let pid = card.playlistId else { return true }
                return !pinIds.contains(pid)
            }
            return HomeSection(id: section.id, title: section.title, kind: section.kind, cards: pinCards + rest)
        }
        return HomeFeed(sections: sections)
    }
}
