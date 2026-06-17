import Foundation

enum HomeFeedRules {
    private static let dailyMixPattern = try! NSRegularExpression(pattern: "(?i)daily mix|daylist")
    private static let discoverPattern = try! NSRegularExpression(pattern: "(?i)discover weekly|new release|fresh find|new to you")
    private static let genreMixPattern = try! NSRegularExpression(pattern: "(?i)\\bmix\\b|essentials|decade|era|hits|party|focus|favorites")
    private static let explicitRadioPattern = try! NSRegularExpression(pattern: "(?i)\\bradio\\b|\\bstation\\b")
    private static let mixLikePattern = try! NSRegularExpression(pattern: "(?i)\\bmix\\b|daily|discover weekly|essentials|station")

    static func isDailyMixName(_ name: String) -> Bool {
        dailyMixPattern.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
    }

    static func isDiscoverName(_ name: String) -> Bool {
        discoverPattern.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
    }

    static func isGenreMixPlaylistName(_ name: String, genre: String? = nil) -> Bool {
        if isDailyMixName(name) || isDiscoverName(name) { return false }
        guard genreMixPattern.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil else { return false }
        guard let genre else { return true }
        return name.localizedCaseInsensitiveContains(genre)
    }

    static func isExplicitRadioPlaylistName(_ name: String) -> Bool {
        let hasMixLike = mixLikePattern.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
        let hasRadio = explicitRadioPattern.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
        if hasMixLike && !hasRadio { return false }
        return hasRadio
    }

    static func historyMatchesGenre(_ row: StreamHistoryItem, genre: String) -> Bool {
        [row.sourceLabel, row.playlist, row.album, row.artist]
            .compactMap { $0 }
            .contains { $0.localizedCaseInsensitiveContains(genre) }
    }

    static func artPathForArtistDistinct(_ history: [StreamHistoryItem], artist: String, used: Set<String>) -> String? {
        history.first { row in
            guard let path = row.filepath, !used.contains(path) else { return false }
            return row.artist?.caseInsensitiveCompare(artist) == .orderedSame
        }?.filepath
    }

    static func artPathForGenreDistinct(_ history: [StreamHistoryItem], genre: String, used: Set<String>) -> String? {
        history.first { row in
            guard let path = row.filepath, !used.contains(path) else { return false }
            return historyMatchesGenre(row, genre: genre)
        }?.filepath
    }

    static func nextDistinctArtPath(_ history: [StreamHistoryItem], used: Set<String>) -> String? {
        history.first { row in
            guard let path = row.filepath else { return false }
            return !used.contains(path)
        }?.filepath
    }

    static func topArtistForGenre(_ history: [StreamHistoryItem], genre: String) -> String? {
        var counts: [String: Int] = [:]
        for row in history {
            guard let artist = row.artist, historyMatchesGenre(row, genre: genre) else { continue }
            counts[artist.lowercased(), default: 0] += 1
        }
        guard let top = counts.max(by: { $0.value < $1.value })?.key else { return nil }
        return history.first { $0.artist?.caseInsensitiveCompare(top) == .orderedSame }?.artist
    }

    static func matchesKeywords(_ text: String, keywords: [String]) -> Bool {
        keywords.contains { text.localizedCaseInsensitiveContains($0) }
    }

    static func playlistSearchText(_ playlist: PlaylistSummary) -> String {
        [playlist.name, playlist.sourceName, playlist.source].compactMap { $0 }.joined(separator: " ")
    }

    static func playlistMatchesTheme(_ playlist: PlaylistSummary, theme: HomeTheme) -> Bool {
        playlistThemeScore(playlist, theme: theme) > 0
    }

    static func playlistMatchesTheme(_ name: String, theme: HomeTheme) -> Bool {
        matchesKeywords(name, keywords: theme.playlistKeywords)
    }

    static func genreMatchesTheme(_ name: String, theme: HomeTheme) -> Bool {
        matchesKeywords(name, keywords: theme.genreKeywords)
    }

    static func historyMatchesTheme(_ row: StreamHistoryItem, theme: HomeTheme) -> Bool {
        let haystack = [row.sourceLabel, row.playlist, row.album, row.artist].compactMap { $0 }
        let keywords = theme.playlistKeywords + theme.genreKeywords
        return haystack.contains { matchesKeywords($0, keywords: keywords) }
    }

    static func topArtistForTheme(_ history: [StreamHistoryItem], theme: HomeTheme) -> String? {
        var counts: [String: Int] = [:]
        for row in history {
            guard let artist = row.artist, historyMatchesTheme(row, theme: theme) else { continue }
            counts[artist.lowercased(), default: 0] += 1
        }
        guard let top = counts.max(by: { $0.value < $1.value })?.key else { return nil }
        return history.first { $0.artist?.caseInsensitiveCompare(top) == .orderedSame }?.artist
    }

    static func matchingLibraryGenre(_ theme: HomeTheme, libraryGenres: [GenreItem]) -> String? {
        libraryGenres.first { genreMatchesTheme($0.name, theme: theme) }?.name
    }

    static func playlistThemeScore(_ playlist: PlaylistSummary, theme: HomeTheme) -> Int {
        playlistThemeScore(playlistSearchText(playlist), theme: theme)
    }

    static func playlistThemeScore(_ name: String, theme: HomeTheme) -> Int {
        let haystack = name.lowercased()
        var score = 0
        for keyword in theme.playlistKeywords where haystack.contains(keyword.lowercased()) {
            score += 10
        }
        for keyword in theme.genreKeywords where haystack.contains(keyword.lowercased()) {
            score += 4
        }
        return score
    }

    static func playlistMatchesMoodSection(_ playlist: PlaylistSummary, theme: HomeTheme) -> Bool {
        matchesKeywords(playlistSearchText(playlist), keywords: theme.playlistKeywords)
    }

    static func playlistMatchesMoodSection(_ name: String, theme: HomeTheme) -> Bool {
        matchesKeywords(name, keywords: theme.playlistKeywords)
    }

    static func playlistsForMoodSection(_ all: [PlaylistSummary], theme: HomeTheme) -> [PlaylistSummary] {
        all
            .filter { $0.tracks > 0 && playlistMatchesMoodSection($0, theme: theme) }
            .sorted {
                let ls = playlistKeywordScore($0, theme: theme)
                let rs = playlistKeywordScore($1, theme: theme)
                if ls != rs { return ls > rs }
                return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
            }
    }

    private static func playlistKeywordScore(_ playlist: PlaylistSummary, theme: HomeTheme) -> Int {
        let haystack = playlistSearchText(playlist).lowercased()
        return theme.playlistKeywords.filter { haystack.contains($0.lowercased()) }.count
    }

    static func playlistsForTheme(_ all: [PlaylistSummary], theme: HomeTheme) -> [PlaylistSummary] {
        all
            .filter { $0.tracks > 0 && playlistThemeScore($0, theme: theme) > 0 }
            .sorted {
                let ls = playlistThemeScore($0, theme: theme)
                let rs = playlistThemeScore($1, theme: theme)
                if ls != rs { return ls > rs }
                return $0.tracks > $1.tracks
            }
    }

    static func isSpecialHomePlaylistName(_ name: String) -> Bool {
        isDailyMixName(name) || isDiscoverName(name) || isGenreMixPlaylistName(name) || isExplicitRadioPlaylistName(name)
    }

    static func browsablePlaylists(_ all: [PlaylistSummary]) -> [PlaylistSummary] {
        all.filter { $0.tracks > 0 && !isSpecialHomePlaylistName($0.name) }
    }

    static func shuffledBrowsablePlaylists(_ all: [PlaylistSummary], seed: UInt64) -> [PlaylistSummary] {
        var generator = SeededRandomNumberGenerator(seed: seed)
        return browsablePlaylists(all).shuffled(using: &generator)
    }
}

struct SeededRandomNumberGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed == 0 ? 0x4d595449 : seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
