import Foundation

enum HomeCachePersistence {
    static let feedLayoutVersion = 3
    private static let fileName = "home_cache.json"
    private static let maxAgeMs: Int64 = 24 * 60 * 60 * 1000

    struct Snapshot {
        let feed: HomeFeed
        let playlistPaths: [String: String]
        let hasRatedSongs: Bool?
    }

    private struct SnapshotDto: Codable {
        let savedAtMs: Int64
        let feedVersion: Int
        let sections: [SectionDto]
        let playlistPaths: [String: String]?
        let hasRatedSongs: Bool?
    }

    private struct SectionDto: Codable {
        let id: String
        let title: String
        let kind: String
        let cards: [CardDto]
    }

    private struct CardDto: Codable {
        let id: String
        let title: String
        let subtitle: String?
        let artPath: String?
        let playlistId: String?
        let kind: String
        let playTarget: PlayTargetDto
    }

    private struct PlayTargetDto: Codable {
        let type: String
        let id: String?
        let name: String?
        let artist: String?
        let path: String?
        let title: String?
        let displayTitle: String?
        let seedKind: String?
    }

    static func save(_ feed: HomeFeed, playlistPaths: [String: String] = HomeArtworkCache.allPlaylistPaths()) {
        guard !feed.sections.isEmpty else { return }
        let dto = SnapshotDto(
            savedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
            feedVersion: feedLayoutVersion,
            sections: feed.sections.map(sectionToDto),
            playlistPaths: playlistPaths.isEmpty ? nil : playlistPaths,
            hasRatedSongs: HomeFeedCache.peekHasRatedSongs()
        )
        guard let url = cacheURL(), let json = try? JSONEncoder().encode(dto) else { return }
        try? json.write(to: url, options: .atomic)
    }

    static func load() -> Snapshot? {
        guard let url = cacheURL(),
              let data = try? Data(contentsOf: url),
              let dto = try? JSONDecoder().decode(SnapshotDto.self, from: data) else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if now - dto.savedAtMs > maxAgeMs || dto.feedVersion < feedLayoutVersion { return nil }
        let sections = dto.sections.compactMap(dtoToSection)
        guard !sections.isEmpty else { return nil }
        let feed = HomeFeed(sections: sections)
        guard feed.isUsableHomeCache(
            activeProfileLinked: ActiveProfileStore.activeMemberId() != nil,
            hasRatedSongs: dto.hasRatedSongs
        ) else { return nil }
        return Snapshot(feed: feed, playlistPaths: dto.playlistPaths ?? [:], hasRatedSongs: dto.hasRatedSongs)
    }

    static func clear() {
        guard let url = cacheURL() else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private static func cacheURL() -> URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent(fileName)
    }

    private static func sectionToDto(_ section: HomeSection) -> SectionDto {
        SectionDto(
            id: section.id,
            title: section.title,
            kind: kindName(section.kind),
            cards: section.cards.map(cardToDto)
        )
    }

    private static func dtoToSection(_ dto: SectionDto) -> HomeSection? {
        guard let kind = parseKind(dto.kind) else { return nil }
        let cards = dto.cards.compactMap(dtoToCard)
        guard !cards.isEmpty else { return nil }
        return HomeSection(id: dto.id, title: dto.title, kind: kind, cards: cards)
    }

    private static func cardToDto(_ card: HomeCard) -> CardDto {
        CardDto(
            id: card.id,
            title: card.title,
            subtitle: card.subtitle,
            artPath: card.artPath,
            playlistId: card.playlistId,
            kind: kindName(card.kind),
            playTarget: targetToDto(card.playTarget)
        )
    }

    private static func dtoToCard(_ dto: CardDto) -> HomeCard? {
        guard let kind = parseKind(dto.kind), let target = dtoToTarget(dto.playTarget) else { return nil }
        return HomeCard(
            id: dto.id,
            title: dto.title,
            subtitle: dto.subtitle,
            artPath: dto.artPath,
            playlistId: dto.playlistId,
            playTarget: target,
            kind: kind
        )
    }

    private static func kindName(_ kind: HomeSectionKind) -> String {
        switch kind {
        case .jumpBackIn: return "jumpBackIn"
        case .favorites: return "favorites"
        case .ratedSongs: return "ratedSongs"
        case .topMixes: return "topMixes"
        case .browseGenres: return "browseGenres"
        case .exploreThemes: return "exploreThemes"
        case .mood: return "mood"
        case .decade: return "decade"
        case .dailyMixes: return "dailyMixes"
        case .recentlyCreated: return "recentlyCreated"
        case .recentPlaylists: return "recentPlaylists"
        case .radio: return "radio"
        case .discover: return "discover"
        case .offline: return "offline"
        }
    }

    private static func parseKind(_ raw: String) -> HomeSectionKind? {
        switch raw {
        case "jumpBackIn": return .jumpBackIn
        case "favorites": return .favorites
        case "ratedSongs": return .ratedSongs
        case "topMixes": return .topMixes
        case "browseGenres": return .browseGenres
        case "exploreThemes": return .exploreThemes
        case "mood": return .mood
        case "decade": return .decade
        case "dailyMixes": return .dailyMixes
        case "recentlyCreated": return .recentlyCreated
        case "recentPlaylists": return .recentPlaylists
        case "radio": return .radio
        case "discover": return .discover
        case "offline": return .offline
        default: return nil
        }
    }

    private static func targetToDto(_ target: PlayTarget) -> PlayTargetDto {
        switch target {
        case .playlist(let id, let name):
            return PlayTargetDto(type: "playlist", id: id, name: name, artist: nil, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .artist(let name):
            return PlayTargetDto(type: "artist", id: nil, name: name, artist: nil, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .album(let name, let artist):
            return PlayTargetDto(type: "album", id: nil, name: name, artist: artist, path: nil, title: nil, displayTitle: nil, seedKind: nil)
        case .song(let path, let title):
            return PlayTargetDto(type: "song", id: nil, name: nil, artist: nil, path: path, title: title, displayTitle: nil, seedKind: nil)
        case .radio(let displayTitle, let seedKind, let name, let path):
            let seed = switch seedKind {
            case .artist: "Artist"
            case .song: "Song"
            case .genre: "Genre"
            }
            return PlayTargetDto(type: "radio", id: nil, name: name, artist: nil, path: path, title: nil, displayTitle: displayTitle, seedKind: seed)
        }
    }

    private static func dtoToTarget(_ dto: PlayTargetDto) -> PlayTarget? {
        switch dto.type {
        case "playlist":
            return .playlist(id: dto.id ?? "", name: dto.name ?? "")
        case "artist":
            guard let name = dto.name else { return nil }
            return .artist(name: name)
        case "album":
            guard let name = dto.name else { return nil }
            return .album(name: name, artist: dto.artist)
        case "song":
            guard let path = dto.path else { return nil }
            return .song(path: path, title: dto.title ?? dto.name ?? "Track")
        case "radio":
            let seed: PlayTarget.RadioSeedKind = switch dto.seedKind?.lowercased() {
            case "song": .song
            case "genre": .genre
            default: .artist
            }
            return .radio(displayTitle: dto.displayTitle ?? dto.name ?? "", seedKind: seed, name: dto.name ?? "", path: dto.path)
        default:
            return nil
        }
    }
}

// hasCurrentHomeLayout / isUsableHomeCache live on HomeFeed in HomeFeed.swift
