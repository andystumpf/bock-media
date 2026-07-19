import XCTest
@testable import BockMedia

final class PlaylistDetailDecodeTests: XCTestCase {
    func testDecodeRatedPlaylistDetail() throws {
        let json = """
        {"id": "rated-stars-5", "name": "5 stars", "source": null, "tracks": [{"path": "/music/a.mp3", "title": "A", "artist": null, "album": null, "stars": 5}], "total": 1, "page": 1, "limit": 100, "sortBy": "title", "order": "asc", "q": null, "rated": true, "stars": 5}
        """
        let data = Data(json.utf8)
        let detail = try JSONCoding.decode(PlaylistDetailResponse.self, from: data)
        XCTAssertEqual(detail.id, "rated-stars-5")
        XCTAssertEqual(detail.tracks.count, 1)
    }
}
