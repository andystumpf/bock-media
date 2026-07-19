import Foundation
import Network

actor ServerEndpointResolver {
    static let shared = ServerEndpointResolver()

    private var cachedURL: String?
    private var cachedAt: Date?
    private let cacheTTL: TimeInterval = 60

    private let lanTimeout: TimeInterval = 3
    private let externalTimeout: TimeInterval = 8

    private var wifiAvailable = true
    private let pathMonitor = NWPathMonitor()
    private let pathQueue = DispatchQueue(label: "com.bockmedia.endpoint.path")

    init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.status == .satisfied
                && (path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet))
            Task { await self?.updateWifiAvailable(wifi) }
        }
        pathMonitor.start(queue: pathQueue)
    }

    private func updateWifiAvailable(_ wifi: Bool) {
        if wifi != wifiAvailable {
            wifiAvailable = wifi
            invalidate()
        }
    }

    func invalidate() {
        cachedURL = nil
        cachedAt = nil
    }

    func prime(_ url: String?) {
        guard let url, !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if cachedURL == nil {
            cachedURL = ServerURL.normalize(url)
            cachedAt = Date()
        }
    }

    static func effectiveLocalURL(_ local: String?) -> String? {
        guard let local, !local.isEmpty, !ServerURL.isLoopbackHost(local) else { return nil }
        return local
    }

    static func pickEndpoint(
        local: String?,
        external: String?,
        localReachable: Bool,
        externalReachable: Bool,
        wifiAvailable: Bool = true
    ) -> String? {
        let local = effectiveLocalURL(local)
        if !wifiAvailable {
            return external?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                .map { ServerURL.normalize($0) }
        }
        if let local, localReachable {
            return ServerURL.normalize(local)
        }
        if let external, !external.isEmpty, externalReachable {
            return ServerURL.normalize(external)
        }
        if let external, !external.isEmpty {
            return ServerURL.normalize(external)
        }
        if let local {
            return ServerURL.normalize(local)
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

        let local = Self.effectiveLocalURL(preferences.localServerURL)
        let external = preferences.externalServerURL?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let localHosts = preferences.localHosts()

        let skipLan = !wifiAvailable
        async let localOk: Bool = {
            guard !skipLan, let local else { return false }
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
            guard let external else { return false }
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
            externalReachable: externalReachable,
            wifiAvailable: wifiAvailable
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

extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
