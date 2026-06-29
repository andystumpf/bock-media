import Foundation
import XCTest

enum ProfileTestAPI {
    struct HouseholdMember: Decodable {
        let id: String
        let name: String
    }

    struct HouseholdResponse: Decodable {
        let members: [HouseholdMember]
    }

    struct ClientPrefsResponse: Decodable {
        let memberPrefs: [String: AnyDecodable]?
        let merged: [String: AnyDecodable]?
    }

    struct AnalyticsResponse: Decodable {
        let totalPlays: Int
    }

    struct AnyDecodable: Decodable {
        let value: Any

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let s = try? container.decode(String.self) { value = s; return }
            if let b = try? container.decode(Bool.self) { value = b; return }
            if let i = try? container.decode(Int.self) { value = i; return }
            value = ""
        }

        var stringValue: String? { value as? String }
        var boolValue: Bool? { value as? Bool }
    }

    private static var baseURL: URL {
        guard let url = URL(string: BockSmokeConfig.serverURL.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            XCTFail("BOCK_TEST_SERVER_URL not set")
            fatalError()
        }
        return url
    }

    private static var token: String {
        let t = BockSmokeConfig.apiToken
        XCTAssertFalse(t.isEmpty, "BOCK_TEST_API_TOKEN not set")
        return t
    }

    private static func request(path: String, query: [String: String] = [:]) async throws -> Data {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var req = URLRequest(url: components.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    static func householdMembers() async throws -> [HouseholdMember] {
        let data = try await request(path: "api/household")
        return try JSONDecoder().decode(HouseholdResponse.self, from: data).members
    }

    static func continueAfterQueue(clientId: String, memberId: String) async throws -> String? {
        let data = try await request(path: "api/clients/prefs", query: [
            "clientId": clientId,
            "memberId": memberId,
        ])
        let decoded = try JSONDecoder().decode(ClientPrefsResponse.self, from: data)
        return decoded.memberPrefs?["continueAfterQueue"]?.stringValue
            ?? decoded.merged?["continueAfterQueue"]?.stringValue
    }

    static func wifiOnly(clientId: String, memberId: String) async throws -> Bool? {
        let data = try await request(path: "api/clients/prefs", query: [
            "clientId": clientId,
            "memberId": memberId,
        ])
        let decoded = try JSONDecoder().decode(ClientPrefsResponse.self, from: data)
        return decoded.memberPrefs?["downloadWifiOnly"]?.boolValue
            ?? decoded.merged?["downloadWifiOnly"]?.boolValue
    }

    static func memberTotalPlays(memberId: String) async throws -> Int {
        let data = try await request(path: "api/analytics", query: ["member": memberId])
        return try JSONDecoder().decode(AnalyticsResponse.self, from: data).totalPlays
    }

    static func flushPrefs(clientId: String) {
        let url = URL(string: "bockmedia://uitest/flush-prefs?clientId=\(clientId)")!
        XCUIDevice.shared.system.open(url)
        sleep(1)
    }

    static func assertRemoteContinue(clientId: String, memberId: String, expected: String, file: StaticString = #file, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            if let actual = try? await continueAfterQueue(clientId: clientId, memberId: memberId), actual == expected {
                return
            }
            flushPrefs(clientId: clientId)
            usleep(400_000)
        }
        let actual = (try? await continueAfterQueue(clientId: clientId, memberId: memberId)) ?? "nil"
        XCTFail("continueAfterQueue for \(memberId) expected \(expected) got \(actual)", file: file, line: line)
    }
}
