import Foundation

/// Server-backed helpers for mobile UI tests (extends profile prefs API).
enum MobileTestAPI {
    private static let artifactPrefix = "uitest-"

    private enum ConfigError: Error {
        case missingServerURL
        case missingToken
    }

    private static func baseURL() throws -> URL {
        let raw = BockSmokeConfig.serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty, let url = URL(string: raw) else { throw ConfigError.missingServerURL }
        return url
    }

    private static func authToken() throws -> String {
        let t = BockSmokeConfig.apiToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { throw ConfigError.missingToken }
        return t
    }

    private static func request(path: String, query: [String: String] = [:]) async throws -> Data {
        var components = URLComponents(url: try baseURL().appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        var req = URLRequest(url: components.url!)
        req.setValue("Bearer \(try authToken())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return data
    }

    static func householdMembers() async throws -> [ProfileTestAPI.HouseholdMember] {
        try await ProfileTestAPI.householdMembers()
    }

    static func healthOk() async -> Bool {
        do {
            let url = try baseURL().appendingPathComponent("api/health")
            var req = URLRequest(url: url)
            req.timeoutInterval = 5
            let (_, response) = try await URLSession.shared.data(for: req)
            return (response as? HTTPURLResponse)?.statusCode == 200
        } catch {
            return false
        }
    }

    static func resolveFixturePlaylistId(preferredName: String = "UITest Small") async -> String? {
        struct PlaylistItem: Decodable { let id: String }
        struct Response: Decodable { let items: [PlaylistItem] }
        do {
            let data = try await request(path: "api/playlists", query: ["search": preferredName, "limit": "5"])
            let decoded = try JSONDecoder().decode(Response.self, from: data)
            if let id = decoded.items.first?.id { return id }
            let fallback = try await request(path: "api/playlists", query: ["limit": "1"])
            return try JSONDecoder().decode(Response.self, from: fallback).items.first?.id
        } catch {
            return nil
        }
    }

    static func uiTestMember() async -> ProfileTestAPI.HouseholdMember? {
        guard let members = try? await householdMembers() else { return nil }
        return members.first {
            $0.name.caseInsensitiveCompare("UI Test") == .orderedSame
                || $0.id.localizedCaseInsensitiveContains("uitest")
        }
    }
}
