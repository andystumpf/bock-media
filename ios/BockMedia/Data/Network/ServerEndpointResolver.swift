import Foundation

actor ServerEndpointResolver {
    static let shared = ServerEndpointResolver()

    private var cachedURL: String?
    private var cachedAt: Date?
    private let cacheTTL: TimeInterval = 30

    func invalidate() {
        cachedURL = nil
        cachedAt = nil
    }

    func resolve(
        preferences: AppPreferences,
        forceRefresh: Bool = false,
        authUsername: String?,
        authPassword: String?,
        authToken: String?
    ) async throws -> String {
        if !forceRefresh, let cachedURL, let cachedAt, Date().timeIntervalSince(cachedAt) < cacheTTL {
            return cachedURL
        }

        let local = preferences.localServerURL
        let external = preferences.externalServerURL
        let localHosts = preferences.localHosts()

        if let local, !local.isEmpty, await probe(base: local, timeout: 2, localHosts: localHosts, user: authUsername, pass: authPassword, token: authToken) {
            return cache(ServerURL.normalize(local))
        }
        if let external, !external.isEmpty, await probe(base: external, timeout: 4, localHosts: localHosts, user: authUsername, pass: authPassword, token: authToken) {
            return cache(ServerURL.normalize(external))
        }
        if let external, !external.isEmpty {
            return cache(ServerURL.normalize(external))
        }
        if let local, !local.isEmpty {
            return cache(ServerURL.normalize(local))
        }
        throw BockAPIError.noServerConfigured
    }

    private func cache(_ url: String) -> String {
        cachedURL = url
        cachedAt = Date()
        return url
    }

    private func probe(
        base: String,
        timeout: TimeInterval,
        localHosts: Set<String>,
        user: String?,
        pass: String?,
        token: String?
    ) async -> Bool {
        let urlString = "\(ServerURL.normalize(base))/api/health"
        guard let url = URL(string: urlString) else { return false }
        var request = URLRequest(url: url, timeoutInterval: timeout)
        request.httpMethod = "GET"
        AuthHeaders.apply(to: &request, localHosts: localHosts, username: user, password: pass, token: token)
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else { return false }
            return (200..<300).contains(http.statusCode)
        } catch {
            return false
        }
    }
}
