import Foundation

final class BockMediaAPIClient {
    private let preferences: AppPreferences
    private var baseURL: String?
    private let session: URLSession

    init(preferences: AppPreferences, session: URLSession = .shared) {
        self.preferences = preferences
        self.session = session
    }

    func setBaseURL(_ url: String) {
        baseURL = ServerURL.normalize(url)
    }

    func invalidateBaseURL() {
        baseURL = nil
    }

    // MARK: - Health / summary

    func health() async throws -> HealthResponse {
        try await get("api/health")
    }

    func summary() async throws -> SummaryResponse {
        try await get("api/summary")
    }

    func dashboardQuick() async throws -> DashboardQuickResponse {
        try await get("api/dashboard/quick")
    }

    // MARK: - Browse

    func playlists(page: Int = 1, limit: Int = 100, search: String = "") async throws -> PlaylistsResponse {
        try await get("api/playlists", query: ["page": "\(page)", "limit": "\(limit)", "search": search])
    }

    func smartPlaylists() async throws -> SmartPlaylistsResponse {
        try await get("api/smart_playlists")
    }

    func createSmartPlaylist(body: [String: Any]) async throws -> OkResponse {
        try await post("api/smart_playlists", body: body)
    }

    func updateSmartPlaylist(id: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/smart_playlists/\(id)", method: "PUT", query: [:], body: body)
    }

    func deleteSmartPlaylist(id: String) async throws -> OkResponse {
        try await request(path: "api/smart_playlists/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func refreshSmartPlaylist(id: String) async throws -> OkResponse {
        try await post("api/smart_playlists/\(id)/refresh", body: [:])
    }

    func deviceGroups() async throws -> DeviceGroupsResponse {
        try await get("api/device_groups")
    }

    func playlistDetail(
        id: String,
        page: Int = 1,
        limit: Int = 100,
        q: String? = nil,
        sortBy: String? = nil,
        order: String? = nil
    ) async throws -> PlaylistDetailResponse {
        var query = ["page": "\(page)", "limit": "\(limit)"]
        if let q, !q.isEmpty { query["q"] = q }
        if let sortBy { query["sortBy"] = sortBy }
        if let order { query["order"] = order }
        return try await get("api/playlists/\(id)", query: query)
    }

    func playlistCover(id: String) async throws -> PlaylistCoverResponse {
        try await get("api/playlists/\(id)/cover")
    }

    func playlistCoversBatch(ids: [String]) async throws -> PlaylistCoversBatchResponse {
        try await post("api/playlists/covers", body: ["ids": ids])
    }

    func search(q: String, limit: Int = 30) async throws -> SearchResponse {
        try await get("api/search", query: ["q": q, "limit": "\(limit)"])
    }

    func searchSuggest(q: String) async throws -> SearchResponse {
        try await get("api/search/suggest", query: ["q": q])
    }

    func continueListening(member: String? = nil) async throws -> ContinueResponse {
        var query: [String: String] = [:]
        if let member { query["member"] = member }
        return try await get("api/continue", query: query)
    }

    func libraryNew(since: String = "7d", limit: Int = 50) async throws -> LibraryNewResponse {
        try await get("api/library/new", query: ["since": since, "limit": "\(limit)"])
    }

    func discoverWeekly(member: String? = nil) async throws -> DiscoverWeeklyResponse {
        var query: [String: String] = [:]
        if let member { query["member"] = member }
        return try await get("api/recommendations/discover-weekly", query: query)
    }

    func playlistFolders() async throws -> PlaylistFoldersResponse {
        try await get("api/playlist_folders")
    }

    func assignPlaylistFolder(playlistId: String, folderId: String?) async throws -> OkResponse {
        try await post("api/playlists/\(playlistId)/folder", body: ["folderId": folderId as Any])
    }

    func playbackHandoff(body: [String: Any]) async throws -> HandoffResponse {
        try await post("api/playback/handoff", body: body)
    }

    func loudnessAnalyzeStatus() async throws -> LoudnessAnalyzeStatus {
        try await get("api/library/analyze-loudness/status")
    }

    func startLoudnessAnalyze(force: Bool = false) async throws -> OkResponse {
        try await post("api/library/analyze-loudness", body: ["force": force])
    }

    func artists(page: Int = 1, limit: Int = 50, search: String = "") async throws -> ArtistsResponse {
        try await get("api/artists", query: ["page": "\(page)", "limit": "\(limit)", "search": search])
    }

    func albums(
        page: Int = 1,
        limit: Int = 50,
        search: String = "",
        artist: String? = nil,
        sort: String? = nil
    ) async throws -> AlbumsResponse {
        var query = ["page": "\(page)", "limit": "\(limit)", "search": search]
        if let artist { query["artist"] = artist }
        if let sort { query["sort"] = sort }
        return try await get("api/albums", query: query)
    }

    func songs(page: Int = 1, limit: Int = 100, search: String = "", artist: String? = nil, album: String? = nil) async throws -> SongsResponse {
        var query = ["page": "\(page)", "limit": "\(limit)", "search": search]
        if let artist { query["artist"] = artist }
        if let album { query["album"] = album }
        return try await get("api/songs", query: query)
    }

    func genres(limit: Int = 20) async throws -> GenresResponse {
        try await get("api/genres", query: ["limit": "\(limit)"])
    }

    func watchFolders() async throws -> [WatchFolder] {
        try await get("api/watchfolders")
    }

    func clearCache() async throws -> OkResponse {
        try await post("api/clearcache", body: [:])
    }

    func createPlaylist(body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists", body: body)
    }

    func updatePlaylist(id: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/playlists/\(id)", method: "PUT", query: [:], body: body)
    }

    func deletePlaylist(id: String) async throws -> OkResponse {
        try await request(path: "api/playlists/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func mergePlaylists(body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists/merge", body: body)
    }

    func sortPlaylist(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists/\(id)/sort", body: body)
    }

    func removePlaylistTrack(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists/\(id)/tracks/remove", body: body)
    }

    func movePlaylistTrack(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists/\(id)/tracks/move", body: body)
    }

    func aiPlaylist(body: [String: Any]) async throws -> AiPlaylistResponse {
        try await post("api/playlists/ai", body: body)
    }

    func mixMuseStatus() async throws -> MixMuseStatusResponse {
        try await get("api/mix-muse/status")
    }

    func mixMuseSimilar(body: [String: Any]) async throws -> AiPlaylistResponse {
        try await post("api/mix-muse/similar", body: body)
    }

    func resonanceMix(body: [String: Any]) async throws -> DiscoveryMixResponse {
        try await post("api/resonance/mix", body: body)
    }

    func resonanceRadio(body: [String: Any]) async throws -> DiscoveryMixResponse {
        try await post("api/resonance/radio", body: body)
    }

    func acquireSuggest(body: [String: Any]) async throws -> AcquireSuggestResponse {
        try await post("api/acquire/suggest", body: body)
    }

    func acquireExplore(limit: Int = 24) async throws -> AcquireSuggestResponse {
        try await get("api/acquire/explore", query: ["limit": String(limit)])
    }

    func plexSyncStatus() async throws -> PlexSyncStatusResponse {
        try await get("api/plex_sync/status")
    }

    func settingsJSON() async throws -> [String: Any] {
        try await jsonObject(path: "api/settings")
    }

    func saveSettingsJSON(_ body: [String: Any]) async throws -> OkResponse {
        try await post("api/settings", body: body)
    }

    func configJSON() async throws -> [String: Any] {
        try await jsonObject(path: "api/config")
    }

    func saveConfigJSON(_ body: [String: Any]) async throws -> OkResponse {
        try await post("api/config", body: body)
    }

    func renameDevice(id: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/devices/\(id)", method: "POST", query: [:], body: body)
    }

    func deleteDevice(id: String) async throws -> OkResponse {
        try await request(path: "api/devices/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func mergeDevice(sourceId: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/devices/\(sourceId)/merge", method: "POST", query: [:], body: body)
    }

    func mergeCandidates() async throws -> MergeCandidatesResponse {
        try await get("api/devices/merge_candidates")
    }

    func dismissMergeCandidate(id: String) async throws -> OkResponse {
        try await post("api/devices/\(id)/dismiss_candidate", body: [:])
    }

    func identifyDevices() async throws -> OkResponse {
        try await post("api/devices/identify", body: [:])
    }

    func identifyStatus() async throws -> IdentifyStatusResponse {
        try await get("api/devices/identify/status")
    }

    func testDevice(body: [String: Any]) async throws -> OkResponse {
        try await post("api/devices/test", body: body)
    }

    func createDeviceGroup(body: [String: Any]) async throws -> OkResponse {
        try await post("api/device_groups", body: body)
    }

    func updateDeviceGroup(id: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/device_groups/\(id)", method: "PUT", query: [:], body: body)
    }

    func deleteDeviceGroup(id: String) async throws -> OkResponse {
        try await request(path: "api/device_groups/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func favorites() async throws -> FavoritesResponse {
        try await get("api/favorites")
    }

    func streamHistory(page: Int = 1, limit: Int = 25) async throws -> StreamHistoryResponse {
        try await get("api/nowplaying", query: ["page": "\(page)", "limit": "\(limit)"])
    }

    func analytics(
        from: String? = nil,
        to: String? = nil,
        deviceId: String? = nil,
        member: String? = nil,
        platform: String? = nil
    ) async throws -> AnalyticsResponse {
        var query: [String: String] = [:]
        if let from { query["from"] = from }
        if let to { query["to"] = to }
        if let deviceId, !deviceId.isEmpty { query["deviceId"] = deviceId }
        if let member, !member.isEmpty { query["member"] = member }
        if let platform, !platform.isEmpty { query["platform"] = platform }
        return try await get("api/analytics", query: query)
    }

    func reportClientEvent(body: [String: Any]) async throws -> OkResponse {
        try await post("api/clients/report", body: body)
    }

    // MARK: - Household / Family

    func household() async throws -> HouseholdResponse {
        try await get("api/household")
    }

    func createMember(body: [String: Any]) async throws -> HouseholdMember {
        try await post("api/household/members", body: body)
    }

    func updateMember(id: String, body: [String: Any]) async throws -> HouseholdMember {
        try await request(path: "api/household/members/\(id)", method: "PUT", query: [:], body: body)
    }

    func deleteMember(id: String) async throws -> OkResponse {
        try await request(path: "api/household/members/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func setMemberPin(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/household/members/\(id)/pin", body: body)
    }

    func bindClient(body: [String: Any]) async throws -> OkResponse {
        try await post("api/clients/bind", body: body)
    }

    func setDeviceOwner(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/devices/\(id)/owner", body: body)
    }

    func clearDeviceOwner(id: String) async throws -> OkResponse {
        try await request(path: "api/devices/\(id)/owner", method: "DELETE", query: [:], body: nil)
    }

    func roomPolicy(id: String) async throws -> RoomPolicy {
        try await get("api/devices/\(id)/policy")
    }

    func setRoomPolicy(id: String, body: [String: Any]) async throws -> RoomPolicy {
        try await post("api/devices/\(id)/policy", body: body)
    }

    func householdAnalytics(from: String? = nil, to: String? = nil) async throws -> HouseholdAnalytics {
        var query: [String: String] = [:]
        if let from, !from.isEmpty { query["from"] = from }
        if let to, !to.isEmpty { query["to"] = to }
        return try await get("api/analytics/household", query: query)
    }

    func messages(member: String? = nil) async throws -> MessagesResponse {
        var query: [String: String] = [:]
        if let member, !member.isEmpty { query["member"] = member }
        return try await get("api/messages", query: query)
    }

    func sendMessage(body: [String: Any]) async throws -> FamilyMessage {
        try await post("api/messages", body: body)
    }

    func sharePlaylist(id: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/playlists/\(id)/share", body: body)
    }

    func roomRequest(deviceId: String, body: [String: Any]) async throws -> OkResponse {
        try await post("api/rooms/\(deviceId)/requests", body: body)
    }

    // MARK: - Playback

    func nowPlayingDevices(viewerClientId: String? = nil) async throws -> NowPlayingDevicesResponse {
        var query: [String: String] = [:]
        if let viewerClientId, !viewerClientId.isEmpty {
            query["viewerClientId"] = viewerClientId
        }
        return try await get("api/nowplaying_devices", query: query)
    }

    func playOnDevice(body: [String: Any]) async throws -> PlayResponse {
        try await post("api/playlists/play", body: body)
    }

    func alexaRemoteStatus() async throws -> AlexaRemoteStatus {
        try await get("api/alexa_remote/status")
    }

    func alexaRemoteDevices() async throws -> AlexaDevicesResponse {
        try await get("api/alexa_remote/devices")
    }

    func alexaRemoteControl(body: [String: Any]) async throws -> PlayResponse {
        try await post("api/alexa_remote/control", body: body)
    }

    func devices() async throws -> [DeviceItem] {
        try await get("api/devices")
    }

    func automations() async throws -> AutomationsResponse {
        try await get("api/automations")
    }

    func createAutomation(body: [String: Any]) async throws -> OkResponse {
        try await post("api/automations", body: body)
    }

    func updateAutomation(id: String, body: [String: Any]) async throws -> OkResponse {
        try await request(path: "api/automations/\(id)", method: "PUT", query: [:], body: body)
    }

    func deleteAutomation(id: String) async throws -> OkResponse {
        try await request(path: "api/automations/\(id)", method: "DELETE", query: [:], body: nil)
    }

    func runAutomation(id: String) async throws -> OkResponse {
        try await post("api/automations/\(id)/run", body: [:])
    }

    func recent(page: Int = 1, limit: Int = 20) async throws -> RecentResponse {
        try await get("api/recent", query: ["page": "\(page)", "limit": "\(limit)"])
    }

    func rooms() async throws -> RoomsResponse {
        try await get("api/rooms")
    }

    func addFavorite(body: [String: Any]) async throws -> OkResponse {
        try await post("api/favorites", body: body)
    }

    func removeFavorite(body: [String: Any]) async throws -> OkResponse {
        try await deleteWithBody("api/favorites", body: body)
    }

    func ignored() async throws -> IgnoredResponse {
        try await get("api/ignored")
    }

    func addIgnored(body: [String: Any]) async throws -> OkResponse {
        try await post("api/ignored", body: body)
    }

    func removeIgnored(body: [String: Any]) async throws -> OkResponse {
        try await deleteWithBody("api/ignored", body: body)
    }

    func analyticsExport(from: String? = nil, to: String? = nil, deviceId: String? = nil) async throws -> Data {
        var query: [String: String] = [:]
        if let from { query["from"] = from }
        if let to { query["to"] = to }
        if let deviceId, !deviceId.isEmpty { query["deviceId"] = deviceId }
        return try await requestData(path: "api/analytics/export", query: query)
    }

    func getVolume(serial: String) async throws -> VolumeResponse {
        try await get("api/alexa_remote/volume", query: ["serial": serial])
    }

    func setVolume(body: [String: Any]) async throws -> OkResponse {
        try await post("api/alexa_remote/volume", body: body)
    }

    func nowPlayingSleep(body: [String: Any]) async throws -> OkResponse {
        try await post("api/nowplaying/sleep", body: body)
    }

    func alexaLoginStart(body: [String: Any] = [:]) async throws -> AlexaRemoteStatus {
        try await post("api/alexa_remote/login/start", body: body)
    }

    func alexaLoginStop() async throws -> AlexaRemoteStatus {
        try await post("api/alexa_remote/login/stop", body: [:])
    }

    // MARK: - HTTP

    private func get<T: Decodable>(_ path: String, query: [String: String] = [:]) async throws -> T {
        try await request(path: path, method: "GET", query: query, body: nil)
    }

    private func post<T: Decodable>(_ path: String, body: [String: Any]?) async throws -> T {
        try await request(path: path, method: "POST", query: [:], body: body)
    }

    private func deleteWithBody<T: Decodable>(_ path: String, body: [String: Any]) async throws -> T {
        try await request(path: path, method: "DELETE", query: [:], body: body)
    }

    private func request<T: Decodable>(
        path: String,
        method: String,
        query: [String: String],
        body: [String: Any]?
    ) async throws -> T {
        let data = try await performRequest(path: path, method: method, query: query, body: body)
        do {
            return try JSONCoding.decode(T.self, from: data)
        } catch {
            throw BockAPIError.decoding(error)
        }
    }

    private func requestData(path: String, query: [String: String] = [:]) async throws -> Data {
        try await performRequest(path: path, method: "GET", query: query, body: nil)
    }

    private func jsonObject(path: String) async throws -> [String: Any] {
        let data = try await performRequest(path: path, method: "GET", query: [:], body: nil)
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw BockAPIError.decoding(DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "Expected JSON object")))
        }
        return object
    }

    private func performRequest(
        path: String,
        method: String,
        query: [String: String],
        body: [String: Any]?
    ) async throws -> Data {
        guard let base = baseURL else { throw BockAPIError.noServerConfigured }
        var components = URLComponents(string: "\(base)/\(path)")!
        if !query.isEmpty {
            components.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = components.url else { throw BockAPIError.invalidURL }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        AuthHeaders.apply(
            to: &request,
            localHosts: preferences.localHosts(),
            username: preferences.adminUser,
            password: preferences.adminPass,
            token: preferences.mobileToken
        )

        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw BockAPIError.transport(URLError(.badServerResponse))
            }
            guard (200..<300).contains(http.statusCode) else {
                let text = String(data: data, encoding: .utf8)
                throw BockAPIError.httpStatus(http.statusCode, text)
            }
            return data
        } catch let error as BockAPIError {
            throw error
        } catch {
            throw BockAPIError.transport(error)
        }
    }
}
