import XCTest
@testable import BockMedia

final class HouseholdDecodeTests: XCTestCase {
    func testDecodesProductionHouseholdShape() throws {
        let json = """
        {
          "members": [
            {
              "avatar": null,
              "color": null,
              "createdAt": 1781988831.9265559,
              "hasPin": true,
              "id": "p-andy",
              "name": "Andy",
              "role": "parent"
            }
          ],
          "deviceOwners": [
            {
              "deviceId": "alexa-1",
              "deviceName": "Kitchen",
              "memberId": "p-andy",
              "memberName": "Andy"
            }
          ],
          "clientBindings": [
            {
              "clientDeviceId": "client-abc",
              "deviceName": "iOS",
              "memberId": "p-andy",
              "memberName": "Andy",
              "platform": null
            }
          ]
        }
        """
        let decoded = try JSONCoding.decode(HouseholdResponse.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.members.count, 1)
        XCTAssertEqual(decoded.members[0].name, "Andy")
        XCTAssertTrue(decoded.members[0].hasPin)
        XCTAssertEqual(decoded.clientBindings.count, 1)
        XCTAssertNil(decoded.clientBindings[0].platform)
    }

    func testMemberMissingHasPinDefaultsFalse() throws {
        let json = """
        {"members":[{"id":"p-x","name":"X","role":"kid"}],"deviceOwners":[],"clientBindings":[]}
        """
        let decoded = try JSONCoding.decode(HouseholdResponse.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.members.count, 1)
        XCTAssertFalse(decoded.members[0].hasPin)
    }

    func testDecodesFullHouseholdFixture() throws {
        let fixture = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("fixtures/demo-data/household_api_sample.json")
        let data = try Data(contentsOf: fixture)
        let decoded = try JSONCoding.decode(HouseholdResponse.self, from: data)
        XCTAssertEqual(decoded.members.count, 5)
        XCTAssertEqual(decoded.members.first?.name, "Andy")
        XCTAssertEqual(decoded.clientBindings.count, 23)
    }
}
