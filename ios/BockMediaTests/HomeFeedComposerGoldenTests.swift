import XCTest
@testable import BockMedia

/// Golden test: a shared JSON fixture (`shared/fixtures/home_feed/`) feeds both the
/// Android and iOS `HomeFeedComposer`. We assert the RNG- and section-order-independent
/// invariants that must hold identically on both platforms — scheduled automation
/// playlists never surface, shortcut tiles are playlist/mix-only, and card ids are unique.
final class HomeFeedComposerGoldenTests: XCTestCase {

    private struct GoldenInput: Decodable {
        var history: [StreamHistoryItem] = []
        var analytics: AnalyticsResponse?
        var allPlaylists: [PlaylistSummary] = []
        var smartPlaylists: [SmartPlaylist] = []
        var favorites: [FavoriteItem] = []
        var dashboard: DashboardQuickResponse?
        var libraryGenres: [GenreItem] = []
        var shuffleSeed: UInt64 = 0
    }

    private struct GoldenExpected: Decodable {
        var forbiddenTitlePrefix: String
        var maxShortcutTiles: Int
        var automationPlaylistName: String
    }

    private func fixtureData(_ name: String) throws -> Data {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("shared/fixtures/home_feed/\(name)")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try Data(contentsOf: candidate)
            }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("shared/fixtures/home_feed/\(name) not found from \(#filePath)")
    }

    func testHomeFeedGolden() throws {
        let decoder = JSONDecoder()
        let g = try decoder.decode(GoldenInput.self, from: fixtureData("input.json"))
        let expected = try decoder.decode(GoldenExpected.self, from: fixtureData("expected.json"))

        // Sanity: the automation playlist is present in the input pool so exclusion is meaningful.
        XCTAssertTrue(g.allPlaylists.contains { $0.name == expected.automationPlaylistName })

        let feed = HomeFeedComposer.compose(
            HomeFeedInput(
                history: g.history,
                analytics: g.analytics,
                allPlaylists: g.allPlaylists,
                smartPlaylists: g.smartPlaylists,
                favorites: g.favorites,
                dashboard: g.dashboard,
                libraryGenres: g.libraryGenres,
                shuffleSeed: g.shuffleSeed
            )
        )

        let allCards = feed.sections.flatMap { $0.cards }
        XCTAssertFalse(allCards.isEmpty, "composer produced an empty feed")

        // 1. Scheduled automation playlists never surface in any section.
        XCTAssertFalse(
            allCards.contains { $0.title.hasPrefix(expected.forbiddenTitlePrefix) },
            "automation playlist leaked into feed"
        )

        // 2. Card ids are globally unique (registry dedup).
        let ids = allCards.map(\.id)
        XCTAssertEqual(ids.count, Set(ids).count, "duplicate card ids in feed")

        // 3. Shortcut tiles: playlist/mix-only, capped, never album/song, never automation.
        let shortcuts = feed.homeShortcutCards(limit: expected.maxShortcutTiles)
        XCTAssertLessThanOrEqual(shortcuts.count, expected.maxShortcutTiles)
        for card in shortcuts {
            switch card.playTarget {
            case .album, .song:
                XCTFail("shortcut tile has album/song target: \(card.title)")
            default:
                break
            }
            XCTAssertFalse(
                card.title.hasPrefix(expected.forbiddenTitlePrefix),
                "shortcut tile is an automation playlist: \(card.title)"
            )
        }
        XCTAssertEqual(shortcuts.count, Set(shortcuts.map(\.id)).count, "duplicate shortcut tiles")
    }
}
