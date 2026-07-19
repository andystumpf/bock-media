import XCTest
@testable import BockMedia

final class AutomationDecodeTests: XCTestCase {
    func testDecodesProductionAutomationsShape() throws {
        let json = """
        {
          "items": [
            {
              "createdAt": 1780410924.9042606,
              "days": [6],
              "device": "GSM4280654060414",
              "deviceName": "Kitchen Show",
              "enabled": true,
              "id": "034154d8-1bf3-45f6-a668-ef61db0dabcd",
              "lastFiredAt": "2026-07-05 07:30",
              "lastRunAt": 1783254608.6726594,
              "lastRunStatus": "ok",
              "name": "Sunday Morning",
              "playlistId": "84486899-5910-58c0-a76a-0d47e83884ed",
              "playlistName": "Morning Coffee",
              "shuffle": false,
              "time": "07:30",
              "updatedAt": 1780410924.9042606,
              "volume": 0
            },
            {
              "createdAt": 1782171626.528157,
              "days": [0,1,2,3,4,5,6],
              "device": "group:c1e9a331-5c52-4689-afe6-e6c91f304031",
              "deviceName": "All Echoes (test)",
              "enabled": false,
              "id": "cc9cba73-0249-44ab-a8ac-a9bd95e69ffb",
              "name": "Test all speakers",
              "playlistId": "55be21e6-561e-43d0-a8c4-40c94f3cab06",
              "playlistName": "Nature Sounds",
              "shuffle": false,
              "time": "03:33",
              "updatedAt": 1782171626.528157
            }
          ]
        }
        """
        let decoded = try JSONCoding.decode(AutomationsResponse.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.items.count, 2)
        XCTAssertEqual(decoded.items[0].name, "Sunday Morning")
        XCTAssertEqual(decoded.items[0].volume, 0)
        XCTAssertEqual(decoded.items[1].device, "group:c1e9a331-5c52-4689-afe6-e6c91f304031")
    }

    func testDecodesFixtureFile() throws {
        let fixture = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("fixtures/demo-data/automations.json")
        let data = try Data(contentsOf: fixture)
        let items = try JSONCoding.decode([AutomationItem].self, from: data)
        XCTAssertEqual(items.count, 3)
        XCTAssertEqual(items[0].days, [0, 1, 2, 3, 4])
    }
}
