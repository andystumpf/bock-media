import XCTest
@testable import BockMedia

final class HomeCardBrowseTests: XCTestCase {
    func testDestination_playlistFromLinkedId() {
        let card = HomeCard(
            id: "pl-1",
            title: "Rock Mix",
            subtitle: nil,
            artPath: nil,
            playlistId: "abc",
            playTarget: .playlist(id: "abc", name: "Rock Mix"),
            kind: .topMixes
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .playlist("abc"))
    }

    func testDestination_artistCardOpensArtist() {
        let card = HomeCard(
            id: "jump-artist",
            title: "Led Zeppelin",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .artist(name: "Led Zeppelin"),
            kind: .jumpBackIn
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .artist("Led Zeppelin"))
    }

    func testDestination_syntheticGenreMixOpensGenreNotArtist() {
        let card = HomeCard(
            id: "mix-Rock",
            title: "Rock Mix",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .artist(name: "Led Zeppelin"),
            kind: .topMixes
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .genre("Rock"))
    }

    func testDestination_syntheticGenreMixWithRadioOpensGenre() {
        let card = HomeCard(
            id: "mix-Rock",
            title: "Rock Mix",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .radio(displayTitle: "Rock Mix", seedKind: .genre, name: "Led Zeppelin", path: nil),
            kind: .topMixes
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .genre("Rock"))
    }

    func testDestination_radioGenreOpensGenre() {
        let card = HomeCard(
            id: "radio-jazz",
            title: "Jazz Radio",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .radio(displayTitle: "Jazz Radio", seedKind: .genre, name: "Jazz", path: nil),
            kind: .radio
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .genre("Jazz"))
    }

    func testDestination_radioGenreUsesTitleNotArtistSeed() {
        let card = HomeCard(
            id: "radio-rock",
            title: "Rock Radio",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .radio(displayTitle: "Rock Radio", seedKind: .genre, name: "Led Zeppelin", path: nil),
            kind: .radio
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .genre("Rock"))
    }

    func testDestination_rotatedPlaylistInBrowseGenresOpensPlaylist() {
        // Tile rotation can replace a stale genre tile with a playlist card that
        // keeps kind=browseGenres — it must open the playlist, not a bogus genre.
        let card = HomeCard(
            id: "pl-rotated",
            title: "This Is Paul Simon",
            subtitle: nil,
            artPath: nil,
            playlistId: "pl-123",
            playTarget: .playlist(id: "pl-123", name: "This Is Paul Simon"),
            kind: .browseGenres
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .playlist("pl-123"))
    }

    func testDestination_genreTileStillOpensGenre() {
        let card = HomeCard(
            id: "browse-genre-Rock",
            title: "Rock",
            subtitle: nil,
            artPath: nil,
            playlistId: nil,
            playTarget: .radio(displayTitle: "Rock Radio", seedKind: .genre, name: "Rock", path: nil),
            kind: .browseGenres
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .genre("Rock"))
    }

    func testDestination_releaseRadarOpensReleaseRadar() {
        let card = HomeCard(
            id: "release-radar",
            title: "Release Radar",
            subtitle: "3 new albums",
            artPath: nil,
            playlistId: nil,
            playTarget: .radio(displayTitle: "New in library", seedKind: .genre, name: "Library", path: nil),
            kind: .discover
        )
        XCTAssertEqual(HomeCardBrowse.destination(for: card), .releaseRadar)
        XCTAssertEqual(HomeCardBrowse.Destination.releaseRadar.searchRoute, .releaseRadar)
    }
}
