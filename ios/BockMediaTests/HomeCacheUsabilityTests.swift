import XCTest
@testable import BockMedia

final class HomeCacheUsabilityTests: XCTestCase {
    private func layoutFeed(includeRatedSection: Bool) -> HomeFeed {
        let rated: [HomeSection]
        if includeRatedSection {
            rated = [
                HomeSection(
                    id: "rated-songs",
                    title: "Rated Songs",
                    kind: .ratedSongs,
                    cards: [
                        HomeCard(
                            id: "rated-stars-5",
                            title: "5★ songs",
                            subtitle: nil,
                            artPath: nil,
                            playlistId: "rated-stars-5",
                            playTarget: .playlist(id: "rated-stars-5", name: "5★ songs"),
                            kind: .ratedSongs
                        ),
                    ]
                ),
            ]
        } else {
            rated = []
        }
        return HomeFeed(sections: rated + [
            HomeSection(
                id: "recently-created",
                title: "Recently Created",
                kind: .recentlyCreated,
                cards: [
                    HomeCard(
                        id: "pl-new",
                        title: "New Mix",
                        subtitle: nil,
                        artPath: nil,
                        playlistId: "pl-new",
                        playTarget: .playlist(id: "pl-new", name: "New Mix"),
                        kind: .recentlyCreated
                    ),
                ]
            ),
            HomeSection(
                id: "browse-genres",
                title: "Browse by genre",
                kind: .browseGenres,
                cards: [
                    HomeCard(
                        id: "genre-rock",
                        title: "Rock",
                        subtitle: nil,
                        artPath: nil,
                        playlistId: "genre-rock",
                        playTarget: .playlist(id: "genre-rock", name: "Rock"),
                        kind: .browseGenres
                    ),
                ]
            ),
            HomeSection(
                id: "recent-playlists",
                title: "Recent playlists",
                kind: .recentPlaylists,
                cards: [
                    HomeCard(
                        id: "pl-1",
                        title: "Playlist 1",
                        subtitle: nil,
                        artPath: nil,
                        playlistId: "pl-1",
                        playTarget: .playlist(id: "pl-1", name: "Playlist 1"),
                        kind: .recentPlaylists
                    ),
                ]
            ),
            HomeSection(
                id: "more-playlists",
                title: "More playlists",
                kind: .recentPlaylists,
                cards: [
                    HomeCard(
                        id: "pl-2",
                        title: "Playlist 2",
                        subtitle: nil,
                        artPath: nil,
                        playlistId: "pl-2",
                        playTarget: .playlist(id: "pl-2", name: "Playlist 2"),
                        kind: .recentPlaylists
                    ),
                ]
            ),
        ])
    }

    func testIsUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsFalse() {
        let feed = layoutFeed(includeRatedSection: false)
        XCTAssertTrue(feed.isUsableHomeCache(activeProfileLinked: true, hasRatedSongs: false))
    }

    func testIsUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsNull() {
        let feed = layoutFeed(includeRatedSection: false)
        XCTAssertTrue(feed.isUsableHomeCache(activeProfileLinked: true, hasRatedSongs: nil))
    }

    func testIsUsableHomeCache_profileLinked_noRatedSection_hasRatedSongsTrue_isStale() {
        let feed = layoutFeed(includeRatedSection: false)
        XCTAssertFalse(feed.isUsableHomeCache(activeProfileLinked: true, hasRatedSongs: true))
    }

    func testShouldRefreshHomeForProfile_onlyWhenRatingsKnownButSectionMissing() {
        let feed = layoutFeed(includeRatedSection: false)
        XCTAssertFalse(shouldRefreshHomeForProfile(activeProfileLinked: true, feed: feed, hasRatedSongs: false))
        XCTAssertFalse(shouldRefreshHomeForProfile(activeProfileLinked: true, feed: feed, hasRatedSongs: nil))
        XCTAssertTrue(shouldRefreshHomeForProfile(activeProfileLinked: true, feed: feed, hasRatedSongs: true))
        XCTAssertFalse(shouldRefreshHomeForProfile(activeProfileLinked: false, feed: feed, hasRatedSongs: true))
    }
}
