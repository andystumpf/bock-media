import Foundation

@MainActor
final class BockMediaRepository: ObservableObject {
    let preferences: AppPreferences
    private let api: BockMediaAPIClient
    private var resolvedBaseURL: String?

    init(preferences: AppPreferences) {
        self.preferences = preferences
        self.api = BockMediaAPIClient(preferences: preferences)
    }

    func invalidateAPI() {
        resolvedBaseURL = nil
        api.invalidateBaseURL()
        HomeArtworkCache.invalidate()
        Task { await ServerEndpointResolver.shared.invalidate() }
    }

    func resolveBaseURL(forceRefresh: Bool = false) async throws -> String {
        if !forceRefresh, let resolvedBaseURL { return resolvedBaseURL }
        let url = try await ServerEndpointResolver.shared.resolve(
            preferences: preferences,
            forceRefresh: forceRefresh,
            authUsername: preferences.adminUser,
            authPassword: preferences.adminPass,
            authToken: preferences.mobileToken
        )
        resolvedBaseURL = url
        api.setBaseURL(url)
        return url
    }

    private func ensureAPI() async throws {
        _ = try await resolveBaseURL()
    }

    func testConnection() async -> Result<HealthResponse, Error> {
        do {
            try await ensureAPI()
            return .success(try await api.health())
        } catch {
            return .failure(error)
        }
    }

    func health() async throws -> HealthResponse {
        try await ensureAPI()
        return try await api.health()
    }

    func summary() async throws -> SummaryResponse {
        try await ensureAPI()
        return try await api.summary()
    }

    func dashboardQuick() async throws -> DashboardQuickResponse {
        try await ensureAPI()
        return try await api.dashboardQuick()
    }

    func playlists(search: String = "", limit: Int = 100) async throws -> PlaylistsResponse {
        try await ensureAPI()
        return try await api.playlists(limit: limit, search: search)
    }

    func playlistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 100,
        q: String? = nil,
        sortBy: String? = nil,
        order: String? = nil
    ) async throws -> PlaylistDetailResponse {
        try await ensureAPI()
        return try await api.playlistDetail(id: id, page: page, limit: limit, q: q, sortBy: sortBy, order: order)
    }

    func playlistCoverPath(id: String) async throws -> String? {
        if let cached = HomeArtworkCache.playlistPath(id: id) { return cached }
        try await ensureAPI()
        guard let path = try await api.playlistCover(id: id).path?.nilIfBlank else { return nil }
        HomeArtworkCache.storePlaylistPath(id: id, path: path)
        return path
    }

    func prefetchPlaylistCoverPaths(ids: [String]) async {
        let missing = Array(Set(ids.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }))
            .filter { HomeArtworkCache.playlistPath(id: $0) == nil }
        guard !missing.isEmpty else { return }
        try? await ensureAPI()
        guard let covers = try? await api.playlistCoversBatch(ids: missing).covers else { return }
        HomeArtworkCache.storePlaylistPaths(covers ?? [:])
    }

    func search(q: String) async throws -> SearchResponse {
        try await ensureAPI()
        return try await api.search(q: q)
    }

    func favorites() async throws -> [FavoriteItem] {
        try await ensureAPI()
        return try await api.favorites().items
    }

    func songs(
        page: Int = 1,
        limit: Int = 100,
        search: String = "",
        artist: String? = nil,
        album: String? = nil
    ) async throws -> SongsResponse {
        try await ensureAPI()
        return try await api.songs(page: page, limit: limit, search: search, artist: artist, album: album)
    }

    func artists(page: Int = 1, limit: Int = 100, search: String = "") async throws -> ArtistsResponse {
        try await ensureAPI()
        return try await api.artists(page: page, limit: limit, search: search)
    }

    func albums(
        page: Int = 1,
        limit: Int = 100,
        search: String = "",
        artist: String? = nil,
        sort: String? = nil
    ) async throws -> AlbumsResponse {
        try await ensureAPI()
        return try await api.albums(page: page, limit: limit, search: search, artist: artist, sort: sort)
    }

    func recentAlbums(limit: Int = 12) async throws -> AlbumsResponse {
        try await albums(page: 1, limit: limit, sort: "year")
    }

    func genres(limit: Int = 20) async throws -> [GenreItem] {
        try await ensureAPI()
        return try await api.genres(limit: limit).items
    }

    func watchFolders() async throws -> [WatchFolder] {
        try await ensureAPI()
        return try await api.watchFolders()
    }

    func clearServerCache() async throws {
        try await ensureAPI()
        _ = try await api.clearCache()
        invalidateAPI()
    }

    func createPlaylist(name: String, tracks: [String] = []) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["name": name]
        if !tracks.isEmpty { body["tracks"] = tracks }
        _ = try await api.createPlaylist(body: body)
    }

    func addPlaylistTrack(playlistId: String, path: String) async throws {
        try await ensureAPI()
        var tracks: [String] = []
        var page = 1
        while true {
            let detail = try await api.playlistDetail(id: playlistId, page: page, limit: 500)
            tracks.append(contentsOf: detail.tracks.compactMap(\.path))
            if detail.tracks.count < 500 || tracks.count >= detail.total { break }
            page += 1
        }
        guard !tracks.contains(path) else { return }
        tracks.append(path)
        _ = try await api.updatePlaylist(id: playlistId, body: ["tracks": tracks])
    }

    func streamHistory(page: Int = 1, limit: Int = 100) async throws -> StreamHistoryResponse {
        try await ensureAPI()
        return try await api.streamHistory(page: page, limit: limit)
    }

    func analytics(from: String? = nil, to: String? = nil) async throws -> AnalyticsResponse {
        try await ensureAPI()
        return try await api.analytics(from: from, to: to)
    }

    func exportAnalyticsCSV(from: String? = nil, to: String? = nil) async throws -> URL {
        try await ensureAPI()
        let data = try await api.analyticsExport(from: from, to: to)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bock_media_streams.csv")
        try data.write(to: url)
        return url
    }

    func deviceGroups() async throws -> DeviceGroupsResponse {
        try await ensureAPI()
        return try await api.deviceGroups()
    }

    func smartPlaylists() async throws -> [SmartPlaylist] {
        try await ensureAPI()
        return try await api.smartPlaylists().items
    }

    func createSmartPlaylist(name: String, genre: String?, artist: String?, maxTracks: Int) async throws {
        try await ensureAPI()
        var rules: [[String: Any]] = []
        if let genre, !genre.isEmpty { rules.append(["type": "genre", "value": genre]) }
        if let artist, !artist.isEmpty { rules.append(["type": "artist", "value": artist]) }
        rules.append(["type": "limit", "value": maxTracks])
        _ = try await api.createSmartPlaylist(body: [
            "name": name,
            "rules": rules,
            "refresh": true,
        ])
    }

    func updateSmartPlaylist(
        id: String,
        name: String,
        genre: String?,
        artist: String?,
        maxTracks: Int,
        enabled: Bool
    ) async throws {
        try await ensureAPI()
        var rules: [[String: Any]] = []
        if let genre, !genre.isEmpty { rules.append(["type": "genre", "value": genre]) }
        if let artist, !artist.isEmpty { rules.append(["type": "artist", "value": artist]) }
        rules.append(["type": "limit", "value": maxTracks])
        _ = try await api.updateSmartPlaylist(id: id, body: [
            "name": name,
            "rules": rules,
            "enabled": enabled,
            "refresh": true,
        ])
    }

    func refreshSmartPlaylist(id: String) async throws {
        try await ensureAPI()
        _ = try await api.refreshSmartPlaylist(id: id)
    }

    func deleteSmartPlaylist(id: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteSmartPlaylist(id: id)
    }

    func reportClientEvent(_ body: [String: Any]) async throws {
        try await ensureAPI()
        _ = try await api.reportClientEvent(body: body)
    }

    func alexaRemoteStatus() async throws -> AlexaRemoteStatus {
        try await ensureAPI()
        return try await api.alexaRemoteStatus()
    }

    func alexaRemoteDevices() async throws -> [AlexaDevice] {
        try await ensureAPI()
        return try await api.alexaRemoteDevices().devices
    }

    func nowPlayingDevices() async throws -> NowPlayingDevicesResponse {
        try await ensureAPI()
        return try await api.nowPlayingDevices(viewerClientId: ClientIdStore.clientId())
    }

    func artworkURL(for path: String?) async -> String? {
        guard let path, !path.isEmpty else { return nil }
        guard let base = try? await resolveBaseURL() else { return nil }
        return ServerURL.artworkURL(base: base, filepath: path)
    }

    func streamURL(for path: String?) async -> String? {
        guard let path, !path.isEmpty else { return nil }
        guard let base = try? await resolveBaseURL() else { return nil }
        return ServerURL.streamURL(base: base, filepath: path)
    }

    func playOnDevice(
        device: String,
        kind: String,
        name: String? = nil,
        id: String? = nil,
        path: String? = nil,
        artist: String? = nil,
        shuffle: Bool = false
    ) async throws -> PlayResponse {
        try await ensureAPI()
        var body: [String: Any] = ["device": device, "kind": kind, "shuffle": shuffle]
        if let name { body["name"] = name }
        if let id { body["id"] = id }
        if let path { body["path"] = path }
        if let artist { body["artist"] = artist }
        return try await api.playOnDevice(body: body)
    }

    func automations() async throws -> AutomationsResponse {
        try await ensureAPI()
        return try await api.automations()
    }

    func resolvePlaylistId(_ name: String) async -> String? {
        guard let list = try? await playlists(limit: 300) else { return nil }
        return list.items.first { $0.name.caseInsensitiveCompare(name) == .orderedSame }?.id
    }

    func deviceControl(deviceId: String, deviceName: String, serial: String?, action: String) async throws -> PlayResponse {
        try await ensureAPI()
        var body: [String: Any] = ["deviceId": deviceId, "device": deviceName, "action": action]
        if let serial { body["serial"] = serial }
        return try await api.alexaRemoteControl(body: body)
    }

    func getVolume(serial: String) async throws -> Int? {
        try await ensureAPI()
        return try await api.getVolume(serial: serial).volume
    }

    func setVolume(serial: String, deviceName: String, volume: Int) async throws {
        try await ensureAPI()
        _ = try await api.setVolume(body: ["serial": serial, "device": deviceName, "volume": volume])
    }

    func setSleep(deviceId: String, minutes: Int? = nil, songs: Int? = nil) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["deviceId": deviceId]
        if let minutes { body["minutes"] = minutes }
        if let songs { body["songs"] = songs }
        _ = try await api.nowPlayingSleep(body: body)
    }

    func deletePlaylist(id: String) async throws {
        try await ensureAPI()
        _ = try await api.deletePlaylist(id: id)
    }

    func mergePlaylists(ids: [String], name: String?) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["ids": ids]
        if let name, !name.isEmpty { body["name"] = name }
        _ = try await api.mergePlaylists(body: body)
    }

    func removePlaylistTrack(playlistId: String, path: String) async throws {
        try await ensureAPI()
        _ = try await api.removePlaylistTrack(id: playlistId, body: ["path": path])
    }

    func sortPlaylist(id: String, sortBy: String, order: String) async throws {
        try await ensureAPI()
        _ = try await api.sortPlaylist(id: id, body: ["sortBy": sortBy, "order": order])
    }

    func aiPlaylist(prompt: String, name: String, maxTracks: Int, save: Bool) async throws -> AiPlaylistResponse {
        try await ensureAPI()
        return try await api.aiPlaylist(body: [
            "prompt": prompt,
            "name": name,
            "maxTracks": maxTracks,
            "save": save,
        ])
    }

    func plexSyncStatus() async throws -> PlexSyncStatusResponse {
        try await ensureAPI()
        return try await api.plexSyncStatus()
    }

    func loadSettingsJSON() async throws -> [String: Any] {
        try await ensureAPI()
        return try await api.settingsJSON()
    }

    func saveSettingsJSON(_ body: [String: Any]) async throws {
        try await ensureAPI()
        _ = try await api.saveSettingsJSON(body)
    }

    func loadConfigJSON() async throws -> [String: Any] {
        try await ensureAPI()
        return try await api.configJSON()
    }

    func saveConfigJSON(_ body: [String: Any]) async throws {
        try await ensureAPI()
        _ = try await api.saveConfigJSON(body)
    }

    func renameDevice(deviceId: String, name: String) async throws {
        try await ensureAPI()
        _ = try await api.renameDevice(id: deviceId, body: ["name": name])
    }

    func deleteDevice(deviceId: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteDevice(id: deviceId)
    }

    func mergeDevices(sourceId: String, targetId: String) async throws {
        try await ensureAPI()
        _ = try await api.mergeDevice(sourceId: sourceId, body: ["targetId": targetId])
    }

    func mergeCandidates() async throws -> [MergeCandidate] {
        try await ensureAPI()
        return try await api.mergeCandidates().candidates
    }

    func dismissMergeCandidate(sourceId: String) async throws {
        try await ensureAPI()
        _ = try await api.dismissMergeCandidate(id: sourceId)
    }

    func identifyDevices() async throws {
        try await ensureAPI()
        _ = try await api.identifyDevices()
    }

    func identifyStatus() async throws -> IdentifyStatusResponse {
        try await ensureAPI()
        return try await api.identifyStatus()
    }

    func testDevice(serial: String) async throws {
        try await ensureAPI()
        _ = try await api.testDevice(body: ["serial": serial])
    }

    func createDeviceGroup(name: String, serials: [String]) async throws {
        try await ensureAPI()
        _ = try await api.createDeviceGroup(body: ["name": name, "devices": serials])
    }

    func updateDeviceGroup(id: String, name: String, serials: [String]) async throws {
        try await ensureAPI()
        _ = try await api.updateDeviceGroup(id: id, body: ["name": name, "devices": serials])
    }

    func deleteDeviceGroup(id: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteDeviceGroup(id: id)
    }

    func startAlexaLogin() async throws -> AlexaRemoteStatus {
        try await ensureAPI()
        let existing = try? await api.alexaRemoteStatus()
        let status = existing?.effectiveLoginStatus
        if status == "waiting" || status == "starting" {
            _ = try? await api.alexaLoginStop()
        }
        let active = try await resolveBaseURL()
        var body: [String: Any] = [:]
        if let activeHost = URL(string: active)?.host,
           let local = preferences.localServerURL,
           let localHost = URL(string: local)?.host,
           activeHost != localHost {
            body["host"] = activeHost
        }
        return try await api.alexaLoginStart(body: body)
    }

    func runAutomation(_ id: String) async throws {
        try await ensureAPI()
        _ = try await api.runAutomation(id: id)
    }

    func deleteAutomation(_ id: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteAutomation(id: id)
    }

    func createAutomation(_ body: [String: Any]) async throws {
        try await ensureAPI()
        _ = try await api.createAutomation(body: body)
    }

    func updateAutomation(_ id: String, body: [String: Any]) async throws {
        try await ensureAPI()
        _ = try await api.updateAutomation(id: id, body: body)
    }

    func recent(page: Int = 1) async throws -> RecentResponse {
        try await ensureAPI()
        return try await api.recent(page: page)
    }

    func rooms() async throws -> RoomsResponse {
        try await ensureAPI()
        return try await api.rooms()
    }

    func addFavorite(path: String, title: String?, artist: String?, album: String?) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["path": path]
        if let title { body["title"] = title }
        if let artist { body["artist"] = artist }
        if let album { body["album"] = album }
        _ = try await api.addFavorite(body: body)
    }

    func removeFavorite(path: String) async throws {
        try await ensureAPI()
        _ = try await api.removeFavorite(body: ["path": path])
    }

    func ignored() async throws -> [IgnoredTrack] {
        try await ensureAPI()
        return try await api.ignored().items
    }

    func addIgnored(path: String) async throws {
        try await ensureAPI()
        _ = try await api.addIgnored(body: ["path": path])
    }

    func removeIgnored(path: String) async throws {
        try await ensureAPI()
        _ = try await api.removeIgnored(body: ["path": path])
    }

    func devices() async throws -> [DeviceItem] {
        try await ensureAPI()
        return try await api.devices()
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
