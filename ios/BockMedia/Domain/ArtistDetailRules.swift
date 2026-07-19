import Foundation

enum ArtistDiscographyFilter: String, CaseIterable {
    case studio, singles, live, compilations, all

    var label: String {
        switch self {
        case .studio: return "Studio"
        case .singles: return "Singles & EPs"
        case .live: return "Live"
        case .compilations: return "Compilations"
        case .all: return "All"
        }
    }
}

enum ArtistDetailRules {
    static func isSingleOrEp(_ album: AlbumItem) -> Bool {
        let name = album.name.lowercased()
        if album.track_count > 0 && album.track_count <= 4 { return true }
        let markers = [" ep", "ep ", "single", " live at ", "live from ", "session", " b-sides", "demo"]
        return markers.contains { name.contains($0) }
    }

    static func isLiveAlbum(_ album: AlbumItem) -> Bool {
        let name = album.name.lowercased()
        return name.contains("live") || name.contains("concert") || name.contains("unplugged")
    }

    static func isCompilationAlbum(_ album: AlbumItem, appearsOnNames: Set<String> = []) -> Bool {
        if appearsOnNames.contains(where: { $0.localizedCaseInsensitiveCompare(album.name) == .orderedSame }) {
            return true
        }
        let name = album.name.lowercased()
        let markers = ["greatest hits", "best of", "anthology", "collection", "compilation", "various"]
        return markers.contains { name.contains($0) }
    }

    static func splitAlbums(_ items: [AlbumItem]) -> (studio: [AlbumItem], singles: [AlbumItem]) {
        var studio: [AlbumItem] = []
        var singles: [AlbumItem] = []
        for album in items {
            if isSingleOrEp(album) { singles.append(album) } else { studio.append(album) }
        }
        return (studio, singles)
    }

    static func filterDiscography(
        _ albums: [AlbumItem],
        filter: ArtistDiscographyFilter,
        appearsOnNames: Set<String> = []
    ) -> [AlbumItem] {
        switch filter {
        case .all:
            return albums
        case .studio:
            return albums.filter { !isSingleOrEp($0) && !isLiveAlbum($0) && !isCompilationAlbum($0, appearsOnNames: appearsOnNames) }
        case .singles:
            return albums.filter { isSingleOrEp($0) }
        case .live:
            return albums.filter { isLiveAlbum($0) }
        case .compilations:
            return albums.filter { isCompilationAlbum($0, appearsOnNames: appearsOnNames) }
        }
    }

    static func latestAlbum(_ albums: [AlbumItem]) -> AlbumItem? {
        albums.max { lhs, rhs in
            let y0 = lhs.year ?? 0
            let y1 = rhs.year ?? 0
            if y0 != y1 { return y0 < y1 }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    static func yearRange(_ albums: [AlbumItem]) -> String? {
        let years = albums.compactMap(\.year).filter { $0 > 0 }
        guard !years.isEmpty else { return nil }
        let min = years.min()!
        let max = years.max()!
        return min == max ? String(min) : "\(min)–\(max)"
    }

    static func matchesArtist(_ artist: String?, filter: String) -> Bool {
        guard let artist, !artist.isEmpty else { return false }
        return artist.localizedCaseInsensitiveCompare(filter) == .orderedSame
            || artist.localizedCaseInsensitiveContains(filter)
            || filter.localizedCaseInsensitiveContains(artist)
    }

    static func sortAlbums(_ albums: [AlbumItem], sort: ArtistAlbumSort) -> [AlbumItem] {
        switch sort {
        case .newest:
            return albums.sorted {
                let y0 = $0.year ?? 0
                let y1 = $1.year ?? 0
                if y0 != y1 { return y0 > y1 }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
        case .oldest:
            return albums.sorted {
                let y0 = $0.year ?? Int.max
                let y1 = $1.year ?? Int.max
                if y0 != y1 { return y0 < y1 }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
        case .nameAz:
            return albums.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    static func topGenres(from tracks: [ArtistDetailTrack], limit: Int = 12) -> [String] {
        var counts: [String: Int] = [:]
        for track in tracks {
            guard let genre = track.genre?.trimmingCharacters(in: .whitespacesAndNewlines), !genre.isEmpty else { continue }
            counts[genre.lowercased(), default: 0] += 1
        }
        return counts.sorted { $0.value > $1.value }
            .prefix(limit)
            .map { $0.key.capitalized }
    }

    static func ratedTracks(for artist: String, in rated: [RatingItem]) -> [RatingItem] {
        rated.filter { matchesArtist($0.artist, filter: artist) && !$0.id.isEmpty }
    }

    static func formatLibraryCount(_ n: Int) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 1_000 { return "\(n / 1_000)K" }
        return "\(n)"
    }

    static func isCompilationAlbumName(_ albumName: String, albumArtist: String?) -> Bool {
        let artist = albumArtist?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !artist.isEmpty else { return false }
        let markers = ["various", "va", "soundtrack", "ost"]
        return markers.contains { marker in
            artist.localizedCaseInsensitiveCompare(marker) == .orderedSame
                || artist.localizedCaseInsensitiveContains(marker)
        }
    }
}

enum ArtistAlbumSort: String, CaseIterable {
    case newest, oldest, nameAz

    var label: String {
        switch self {
        case .newest: return "Newest"
        case .oldest: return "Oldest"
        case .nameAz: return "A–Z"
        }
    }
}
