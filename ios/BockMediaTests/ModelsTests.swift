import XCTest
@testable import BockMedia

final class ModelsTests: XCTestCase {
    func testNowPlayingProgressFraction() {
        let p = NowPlayingProgress(elapsedMs: 30_000, durationMs: 120_000)
        XCTAssertEqual(p.fraction, 0.25, accuracy: 0.001)
    }

    func testFormatPlaybackTime() {
        XCTAssertEqual(formatPlaybackTime(seconds: 125), "2:05")
    }

    func testBuildRoutinePhraseShuffle() {
        XCTAssertTrue(buildRoutinePhrase(playlist: "Chill", shuffle: true).contains("mix"))
    }

    func testPlayTargetLabel() {
        XCTAssertEqual(PlayTarget.playlist(id: "1", name: "Jazz").label, "Jazz")
    }

    func testHomeFeedComposerFillsFromLibraryWhenHistoryEmpty() {
        let playlists = (1...30).map { i in
            PlaylistSummary(id: "pl-\(i)", name: "Playlist \(i)", trackCount: i * 2)
        }
        let input = HomeFeedInput(
            history: [],
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 42
        )
        let feed = HomeFeedComposer.compose(input)
        XCTAssertFalse(feed.sections.isEmpty)
        let playlistCards = feed.sections.flatMap(\.cards).filter { $0.playlistId != nil }
        XCTAssertGreaterThanOrEqual(playlistCards.count, 20)
    }

    func testHomeFeedComposerDedupesPlaylistsAcrossSections() {
        let playlists = [
            PlaylistSummary(id: "a", name: "Alpha", trackCount: 10),
            PlaylistSummary(id: "b", name: "Beta", trackCount: 8),
            PlaylistSummary(id: "c", name: "Gamma", trackCount: 6),
        ]
        let history = [
            StreamHistoryItem(playlist: "Alpha", filepath: "/a.mp3"),
            StreamHistoryItem(playlist: "Beta", filepath: "/b.mp3"),
        ]
        let input = HomeFeedInput(
            history: history,
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 7
        )
        let feed = HomeFeedComposer.compose(input)
        let playlistIds = feed.sections.flatMap(\.cards).compactMap(\.playlistId)
        XCTAssertEqual(Set(playlistIds).count, playlistIds.count)
    }

    func testHomeFeedComposerIncludesExploreThemes() {
        let playlists = [
            PlaylistSummary(id: "fr-1", name: "French Favorites", trackCount: 42),
            PlaylistSummary(id: "it-1", name: "Italian Classics", trackCount: 30),
        ]
        let input = HomeFeedInput(
            history: [],
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 1
        )
        let feed = HomeFeedComposer.compose(input)
        let explore = feed.sections.first { $0.kind == .exploreThemes }
        XCTAssertNotNil(explore)
        let titles = explore?.cards.map(\.title) ?? []
        XCTAssertTrue(titles.contains { $0.localizedCaseInsensitiveContains("French") })
        XCTAssertTrue(titles.contains { $0.localizedCaseInsensitiveContains("Italian") })
    }

    func testSearchSongFilterDropsSoundtrackSuffix() {
        let hits = [
            SearchHit(title: "Main Theme - from The Matrix", album: "Main Theme", path: "/a.mp3"),
            SearchHit(title: "Main Theme", album: "Soundtracks", path: "/b.mp3"),
        ]
        let filtered = SearchSongFilter.filter(query: "main theme", songs: hits)
        XCTAssertEqual(filtered.count, 1)
        XCTAssertEqual(filtered.first?.path, "/b.mp3")
    }

    func testMoodSectionCollectsFrenchPlaylists() {
        let playlists = [
            PlaylistSummary(id: "f1", name: "French Pop Hits", trackCount: 10),
            PlaylistSummary(id: "f2", name: "My French Mix", trackCount: 8),
            PlaylistSummary(id: "rock", name: "Rock Classics", trackCount: 20),
        ]
        let input = HomeFeedInput(
            history: [],
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 9
        )
        let feed = HomeFeedComposer.compose(input)
        let french = feed.sections.first { $0.title == "French music" }
        XCTAssertNotNil(french)
        XCTAssertEqual(french?.cards.count, 2)
    }

    func testHomeFeedHasCurrentLayout() {
        let playlists = (1...20).map { i in
            PlaylistSummary(id: "pl-\(i)", name: "Playlist \(i)", trackCount: i)
        }
        let input = HomeFeedInput(
            history: [],
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 2
        )
        let feed = HomeFeedComposer.compose(input)
        XCTAssertTrue(feed.hasCurrentHomeLayout())
    }

    func testHomeTileRotationReplacesStaleTile() {
        HomeTileEngagement.resetForTesting()
        let playlists = (1...12).map { i in
            PlaylistSummary(id: "pl-\(i)", name: "Playlist \(i)", trackCount: i + 3)
        }
        let staleCard = HomeCard(
            id: "pl-pl-1",
            title: "Playlist 1",
            subtitle: "Old",
            artPath: nil,
            playlistId: "pl-1",
            playTarget: .playlist(id: "pl-1", name: "Playlist 1"),
            kind: .discover
        )
        let feed = HomeFeed(sections: [
            HomeSection(id: "discover", title: "Discover", kind: .discover, cards: [staleCard]),
        ])
        let input = HomeFeedInput(
            history: [],
            analytics: nil,
            allPlaylists: playlists,
            smartPlaylists: [],
            favorites: [],
            dashboard: nil,
            libraryGenres: [],
            shuffleSeed: 99
        )
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let staleSince = nowMs - HomeTileEngagement.staleMs - 60_000
        HomeTileEngagement.putForTesting(
            cardId: staleCard.id,
            entry: TileEngagementEntry(firstSeenMs: staleSince)
        )

        let rotated = HomeTileRotation.apply(feed, input: input, nowMs: nowMs)
        let card = rotated.sections[0].cards[0]
        XCTAssertNotEqual(staleCard.id, card.id)
        XCTAssertEqual(card.kind, .discover)
    }
}
