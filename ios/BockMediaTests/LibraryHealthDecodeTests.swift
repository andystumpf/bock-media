import XCTest
@testable import BockMedia

final class LibraryHealthDecodeTests: XCTestCase {
    func testDecodeLibraryHealthResponse() throws {
        let json = """
        {
          "summary": {
            "totalTracks": 100,
            "missingGenre": 2,
            "missingAlbumArtist": 3,
            "needsAttention": 5
          },
          "attentionFolders": [
            { "path": "/music/untagged", "trackCount": 5 }
          ],
          "duplicateArtists": [
            {
              "canonical": "Beatles",
              "variants": ["The Beatles", "Beatles, The"],
              "totalTracks": 42
            }
          ]
        }
        """
        let health = try JSONCoding.decode(LibraryHealthResponse.self, from: Data(json.utf8))
        XCTAssertEqual(health.summary.needsAttention, 5)
        XCTAssertEqual(health.attentionFolders.count, 1)
        XCTAssertEqual(health.duplicateArtists.first?.canonical, "Beatles")
    }
}
