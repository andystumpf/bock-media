import Foundation

actor ServerEndpointResolver {
    static let shared = ServerEndpointResolver()

    private var cachedURL: String?
    private var cachedAt: Date?
    private let cacheTTL: TimeInterval = 60

    private let lanTimeout: TimeInterval = 8
    private let externalTimeout: TimeInterval = 10

    func invalidate() {
        cachedURL = nil
        cachedAt = nil
    }

    static func pickEndpoint(
        local: String?,
        external: String?,
        localReachable: Bool,
        externalReachable: Bool
    ) -> String? {
        if let local, !local.isEmpty, localReachable {
            return ServerURL.normalize(local)
        }
        if let external, !external.isEmpty, externalReachable {
            return ServerURL.normalize(external)
        }
        if let local, !local.isEmpty {
            return ServerURL.normalize(local)
        }
        if let external, !external.isEmpty {
            return ServerURL.normalize(external)
        }
        return nil
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

        async let localOk: Bool = {
            guard let local, !local.isEmpty else { return false }
            return await probe(
                base: local,
                timeout: lanTimeout,
                localHosts: localHosts,
                user: authUsername,
                pass: authPassword,
                token: authToken
            )
        }()
        async let externalOk: Bool = {
            guard let external, !external.isEmpty else { return false }
            return await probe(
                base: external,
                timeout: externalTimeout,
                localHosts: localHosts,
                user: authUsername,
                pass: authPassword,
                token: authToken
            )
        }()

        let localReachable = await localOk
        let externalReachable = await externalOk

        let chosen = Self.pickEndpoint(
            local: local,
            external: external,
            localReachable: localReachable,
            externalReachable: externalReachable
        )
        guard let chosen else { throw BockAPIError.noServerConfigured }
        if localReachable || externalReachable {
            return cache(chosen)
        }
        return chosen
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
