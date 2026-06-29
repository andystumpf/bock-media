import Foundation

@MainActor
final class BockMediaRepository: ObservableObject {
    let preferences: AppPreferences
    private let api: BockMediaAPIClient
    private var resolvedBaseURL: String?
    private var artistPortraitPathCache: [String: String] = [:]
    private var artistPortraitMissCache: Set<String> = []
    private var lyricsCache: [String: LyricsResponse] = [:]

    init(preferences: AppPreferences) {
        self.preferences = preferences
        self.api = BockMediaAPIClient(preferences: preferences)
    }

    func invalidateAPI() {
        resolvedBaseURL = nil
        api.invalidateBaseURL()
        HomeArtworkCache.invalidate()
        artistPortraitPathCache.removeAll()
        artistPortraitMissCache.removeAll()
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

    func playlists(search: String = "", limit: Int = 100, memberScoped: Bool = false) async throws -> PlaylistsResponse {
        try await ensureAPI()
        let member = memberScoped ? scopedMember(nil) : nil
        return try await api.playlists(limit: limit, search: search, member: member)
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

    func search(
        q: String,
        limit: Int = 30,
        preview: Int = 5,
        section: String? = nil,
        source: String? = nil
    ) async throws -> SearchResponse {
        try await ensureAPI()
        var response = try await api.search(
            q: q,
            limit: limit,
            preview: preview,
            section: section,
            source: source
        )
        response.songs = SearchSongFilter.filter(query: q, songs: response.songs)
        return response
    }

    func searchPins() async throws -> [SearchPin] {
        try await ensureAPI()
        return try await api.searchPins(
            memberId: scopedMember(nil),
            clientId: ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        ).pins
    }

    func saveSearchPins(_ pins: [SearchPin]) async throws {
        try await ensureAPI()
        var body: [String: Any] = [
            "pins": pins.map { pin in
                var row: [String: Any] = ["kind": pin.kind]
                if let title = pin.title { row["title"] = title }
                if let name = pin.name { row["name"] = name }
                if let id = pin.id { row["id"] = id }
                if let artist = pin.artist { row["artist"] = artist }
                if let path = pin.path { row["path"] = path }
                return row
            },
        ]
        if let member = scopedMember(nil) { body["memberId"] = member }
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        if !clientId.isEmpty { body["clientId"] = clientId }
        _ = try await api.saveSearchPins(body: body)
    }

    func favorites() async throws -> [FavoriteItem] {
        try await ensureAPI()
        let member = scopedMember(nil)
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        return try await api.favorites(
            member: member,
            clientId: clientId.isEmpty ? nil : clientId,
        ).items
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

    func analytics(from: String? = nil, to: String? = nil, deviceId: String? = nil, member: String? = nil) async throws -> AnalyticsResponse {
        try await ensureAPI()
        return try await api.analytics(from: from, to: to, deviceId: deviceId, member: scopedMember(member))
    }

    func exportAnalyticsCSV(from: String? = nil, to: String? = nil, deviceId: String? = nil, member: String? = nil) async throws -> URL {
        try await ensureAPI()
        let data = try await api.analyticsExport(from: from, to: to, deviceId: deviceId, member: scopedMember(member))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("bock_media_streams.csv")
        try data.write(to: url)
        return url
    }

    /// Server device-id for this phone's own plays. Mirrors Android `clientDeviceId()`.
    func clientDeviceId() -> String {
        let cid = ClientIdStore.clientId().trimmingCharacters(in: .whitespaces).lowercased()
        return cid.isEmpty ? "" : "client-\(cid)"
    }

    func deviceGroups() async throws -> DeviceGroupsResponse {
        try await ensureAPI()
        return try await api.deviceGroups()
    }

    func smartPlaylists() async throws -> [SmartPlaylist] {
        try await ensureAPI()
        return try await api.smartPlaylists().items
    }

    func searchSuggest(q: String) async throws -> SearchResponse {
        try await ensureAPI()
        return try await api.searchSuggest(q: q)
    }

    func continueListening(member: String? = nil) async throws -> ContinueResponse {
        try await ensureAPI()
        return try await api.continueListening(member: scopedMember(member))
    }

    func libraryNew(since: String = "7d") async throws -> LibraryNewResponse {
        try await ensureAPI()
        return try await api.libraryNew(since: since)
    }

    func discoverWeekly(member: String? = nil) async throws -> DiscoverWeeklyResponse {
        try await ensureAPI()
        return try await api.discoverWeekly(member: scopedMember(member))
    }

    func playlistFolders() async throws -> PlaylistFoldersResponse {
        try await ensureAPI()
        return try await api.playlistFolders()
    }

    func playbackHandoff(fromDeviceId: String, toDeviceId: String, offsetMs: Int, context: [String: Any]) async throws -> HandoffResponse {
        try await ensureAPI()
        return try await api.playbackHandoff(body: [
            "fromDeviceId": fromDeviceId,
            "toDeviceId": toDeviceId,
            "offsetMs": offsetMs,
            "context": context,
        ])
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

    func reportClientEvent(_ body: [String: Any]) async throws -> OkResponse {
        try await ensureAPI()
        return try await api.reportClientEvent(body: body)
    }

    // MARK: - Household / Family

    func household() async throws -> HouseholdResponse {
        try await ensureAPI()
        return try await api.household()
    }

    func createMember(name: String, role: String) async throws -> HouseholdMember {
        try await ensureAPI()
        return try await api.createMember(body: ["name": name, "role": role])
    }

    func updateMember(id: String, role: String) async throws -> HouseholdMember {
        try await ensureAPI()
        return try await api.updateMember(id: id, body: ["role": role])
    }

    func deleteMember(id: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteMember(id: id)
    }

    func setMemberPin(id: String, pin: String, currentPin: String?) async throws -> OkResponse {
        try await ensureAPI()
        var body: [String: Any] = ["pin": pin]
        if let currentPin, !currentPin.isEmpty { body["currentPin"] = currentPin }
        return try await api.setMemberPin(id: id, body: body)
    }

    func bindClient(clientId: String, memberId: String?, phoneId: String? = nil) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["clientId": clientId]
        if let memberId, !memberId.isEmpty { body["memberId"] = memberId }
        if let phoneId, !phoneId.isEmpty { body["phoneId"] = phoneId }
        _ = try await api.bindClient(body: body)
    }

    func connectInstall(phoneId: String, deviceName: String, clientId: String) async throws -> String? {
        try await ensureAPI()
        var body: [String: Any] = [
            "clientId": clientId,
            "platform": "ios",
            "deviceName": deviceName,
            "event": "connect",
        ]
        if !phoneId.isEmpty { body["phoneId"] = phoneId }
        let response = try await api.reportClientEvent(body: body)
        return response.memberId?.nilIfBlank
    }

    func clientPrefs(clientId: String, memberId: String?) async throws -> ClientPrefsResponse {
        try await ensureAPI()
        return try await api.clientPrefs(clientId: clientId, memberId: memberId)
    }

    func putClientPrefs(
        clientId: String,
        memberId: String?,
        memberPrefs: [String: Any],
        clientPrefs: [String: Any]
    ) async throws -> ClientPrefsResponse {
        try await ensureAPI()
        var body: [String: Any] = ["clientId": clientId]
        if let memberId, !memberId.isEmpty { body["memberId"] = memberId }
        if !memberPrefs.isEmpty { body["memberPrefs"] = memberPrefs }
        if !clientPrefs.isEmpty { body["clientPrefs"] = clientPrefs }
        return try await api.putClientPrefs(body: body)
    }

    func setDeviceOwner(deviceId: String, memberId: String?) async throws {
        try await ensureAPI()
        if let memberId, !memberId.isEmpty {
            _ = try await api.setDeviceOwner(id: deviceId, body: ["memberId": memberId])
        } else {
            _ = try await api.clearDeviceOwner(id: deviceId)
        }
    }

    func roomPolicy(deviceId: String) async throws -> RoomPolicy {
        try await ensureAPI()
        return try await api.roomPolicy(id: deviceId)
    }

    func setRoomPolicy(deviceId: String, body: [String: Any]) async throws -> RoomPolicy {
        try await ensureAPI()
        return try await api.setRoomPolicy(id: deviceId, body: body)
    }

    func householdAnalytics() async throws -> HouseholdAnalytics {
        try await ensureAPI()
        return try await api.householdAnalytics()
    }

    func messages(member: String?) async throws -> MessagesResponse {
        try await ensureAPI()
        return try await api.messages(member: member)
    }

    func sendMessage(fromMemberId: String?, toMemberId: String?, text: String) async throws -> FamilyMessage {
        try await ensureAPI()
        var body: [String: Any] = [
            "text": text,
            "clientId": ClientIdStore.clientId(),
            "scope": (toMemberId?.isEmpty == false) ? "direct" : "household",
        ]
        if let fromMemberId, !fromMemberId.isEmpty { body["fromMemberId"] = fromMemberId }
        if let toMemberId, !toMemberId.isEmpty { body["toMemberId"] = toMemberId }
        return try await api.sendMessage(body: body)
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

    func lyrics(
        path: String,
        durationSec: Int? = nil,
        title: String? = nil,
        artist: String? = nil,
        album: String? = nil
    ) async -> LyricsResponse? {
        guard !path.isEmpty else { return nil }
        let key = [path, durationSec.map(String.init), title, artist, album]
            .compactMap { $0 }
            .joined(separator: "|")
        if let cached = lyricsCache[key] { return cached }
        do {
            try await ensureAPI()
            let resp = try await api.lyrics(
                path: path,
                durationSec: durationSec,
                title: title,
                artist: artist,
                album: album
            )
            if !resp.lines.isEmpty {
                lyricsCache[key] = resp
            }
            return resp
        } catch {
            return nil
        }
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

    func seekQueueIndex(deviceId: String, deviceName: String, serial: String?, relativeIndex: Int) async throws -> PlayResponse {
        try await ensureAPI()
        var body: [String: Any] = [
            "deviceId": deviceId,
            "device": deviceName,
            "action": "seek_queue_index",
            "index": relativeIndex,
            "relative": true,
        ]
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

    /// Keep a daily playlist forever — stops the daily regenerator from overwriting it.
    func saveDailyPlaylist(id: String, name: String?) async throws {
        try await ensureAPI()
        var body: [String: Any] = [:]
        if let name, !name.isEmpty { body["name"] = name }
        _ = try await api.saveDailyPlaylist(id: id, body: body)
    }

    func sharePlaylist(id: String, toMemberIds: [String]) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["toMemberIds": toMemberIds]
        if let member = scopedMember(nil), !member.isEmpty { body["memberId"] = member }
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        if !clientId.isEmpty { body["clientId"] = clientId }
        _ = try await api.sharePlaylist(id: id, body: body)
    }

    func roomRequest(deviceId: String, path: String, track: String?, artist: String?) async throws -> RoomRequestItem {
        try await ensureAPI()
        var body: [String: Any] = ["path": path]
        if let track, !track.isEmpty { body["track"] = track }
        if let artist, !artist.isEmpty { body["artist"] = artist }
        if let member = scopedMember(nil), !member.isEmpty { body["memberId"] = member }
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        if !clientId.isEmpty { body["clientId"] = clientId }
        return try await api.roomRequest(deviceId: deviceId, body: body)
    }

    func roomQueue(deviceId: String) async throws -> RoomQueueResponse {
        try await ensureAPI()
        return try await api.roomQueue(deviceId: deviceId)
    }

    func approveRoomRequest(deviceId: String, requestId: String, pin: String) async throws -> RoomRequestItem {
        try await ensureAPI()
        var body: [String: Any] = ["pin": pin]
        if let member = scopedMember(nil), !member.isEmpty { body["memberId"] = member }
        return try await api.approveRoomRequest(deviceId: deviceId, requestId: requestId, body: body)
    }

    func deleteRoomRequest(deviceId: String, requestId: String) async throws {
        try await ensureAPI()
        _ = try await api.deleteRoomRequest(deviceId: deviceId, requestId: requestId)
    }

    func reorderRoomRequests(deviceId: String, order: [String]) async throws -> RoomQueueResponse {
        try await ensureAPI()
        return try await api.reorderRoomRequests(deviceId: deviceId, body: ["order": order])
    }

    func removePlaylistTrack(playlistId: String, path: String) async throws {
        try await ensureAPI()
        _ = try await api.removePlaylistTrack(id: playlistId, body: ["path": path])
    }

    func sortPlaylist(id: String, sortBy: String, order: String) async throws {
        try await ensureAPI()
        _ = try await api.sortPlaylist(id: id, body: ["by": sortBy, "order": order])
    }

    func movePlaylistTrack(playlistId: String, path: String, toIndex: Int) async throws {
        try await ensureAPI()
        _ = try await api.movePlaylistTrack(
            id: playlistId,
            body: ["path": path, "toIndex": toIndex]
        )
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

    func mixMuseSimilar(seedKind: String, path: String? = nil, album: String? = nil,
                        artist: String? = nil, playlistId: String? = nil,
                        prompt: String? = nil, maxTracks: Int = 25, save: Bool = true) async throws -> AiPlaylistResponse {
        try await ensureAPI()
        var body: [String: Any] = ["seedKind": seedKind, "maxTracks": maxTracks, "save": save]
        if let path { body["path"] = path }
        if let album { body["album"] = album }
        if let artist { body["artist"] = artist }
        if let playlistId { body["playlistId"] = playlistId }
        if let prompt { body["prompt"] = prompt }
        return try await api.mixMuseSimilar(body: body)
    }

    func resonanceRadio(seedKind: String, path: String? = nil, album: String? = nil,
                        artist: String? = nil, playlistId: String? = nil,
                        maxTracks: Int = 30) async throws -> DiscoveryMixResponse {
        try await ensureAPI()
        var body: [String: Any] = ["seedKind": seedKind, "maxTracks": maxTracks]
        if let path { body["path"] = path }
        if let album { body["album"] = album }
        if let artist { body["artist"] = artist }
        if let playlistId { body["playlistId"] = playlistId }
        return try await api.resonanceRadio(body: body)
    }

    func resonanceMix(seedKind: String, path: String? = nil, album: String? = nil,
                      artist: String? = nil, playlistId: String? = nil,
                      maxTracks: Int = 30, save: Bool = true) async throws -> DiscoveryMixResponse {
        try await ensureAPI()
        var body: [String: Any] = ["seedKind": seedKind, "maxTracks": maxTracks, "save": save]
        if let path { body["path"] = path }
        if let album { body["album"] = album }
        if let artist { body["artist"] = artist }
        if let playlistId { body["playlistId"] = playlistId }
        return try await api.resonanceMix(body: body)
    }

    func acquireSuggest(seedKind: String, path: String? = nil, album: String? = nil,
                        artist: String? = nil, playlistId: String? = nil,
                        limit: Int = 24) async throws -> AcquireSuggestResponse {
        try await ensureAPI()
        var body: [String: Any] = ["seedKind": seedKind, "limit": limit]
        if let path { body["path"] = path }
        if let album { body["album"] = album }
        if let artist { body["artist"] = artist }
        if let playlistId { body["playlistId"] = playlistId }
        return try await api.acquireSuggest(body: body)
    }

    func acquireExplore(limit: Int = 24) async throws -> AcquireSuggestResponse {
        try await ensureAPI()
        return try await api.acquireExplore(limit: limit)
    }

    func playDiscoveryTracksLocally(_ tracks: [PlaylistTrack], title: String, shuffle: Bool = true) async {
        var localTracks: [LocalTrack] = []
        for t in tracks {
            guard let path = t.path, !path.isEmpty else { continue }
            guard let urlStr = await streamURL(for: path), let url = URL(string: urlStr) else { continue }
            localTracks.append(LocalTrack(path: path, title: t.title ?? path, artist: t.artist, album: t.album, streamURL: url, localFileURL: nil))
        }
        guard !localTracks.isEmpty else { return }
        LocalPlaybackController.shared.setPlayContext(
            repository: self,
            target: .radio(displayTitle: title, seedKind: .artist, name: title, path: nil)
        )
        try? await LocalPlaybackController.shared.playTracks(localTracks, shuffle: shuffle)
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
        let trim = { (s: String) -> String in s.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
        if let localRaw = preferences.localServerURL {
            let local = trim(localRaw)
            let activeTrim = trim(active)
            if !local.isEmpty, activeTrim.hasPrefix(local), let h = URL(string: localRaw)?.host {
                body["host"] = h
            } else if let h = URL(string: active)?.host {
                body["host"] = h
            }
        } else if let h = URL(string: active)?.host {
            body["host"] = h
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
        if let member = scopedMember(nil), !member.isEmpty { body["memberId"] = member }
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        if !clientId.isEmpty { body["clientId"] = clientId }
        _ = try await api.addFavorite(body: body)
    }

    func removeFavorite(path: String) async throws {
        try await ensureAPI()
        var body: [String: Any] = ["path": path]
        if let member = scopedMember(nil), !member.isEmpty { body["memberId"] = member }
        let clientId = ClientIdStore.clientId().trimmingCharacters(in: .whitespacesAndNewlines)
        if !clientId.isEmpty { body["clientId"] = clientId }
        _ = try await api.removeFavorite(body: body)
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

    func artistPortraitPath(for artistName: String) async -> String? {
        let key = artistName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !key.isEmpty else { return nil }
        if let cached = artistPortraitPathCache[key] { return cached.nilIfBlank }
        if artistPortraitMissCache.contains(key) { return nil }
        do {
            try await ensureAPI()
            let response = try await api.artistPortrait(artist: artistName)
            if let path = response.artPath?.nilIfBlank {
                artistPortraitPathCache[key] = path
                return path
            }
        } catch {}
        artistPortraitMissCache.insert(key)
        return nil
    }

    func resolveLibraryArtUrl(for item: LibraryItem) async -> URL? {
        if let path = item.artPath, let str = await artworkURL(for: path), let url = URL(string: str) {
            return url
        }
        if let playlistId = item.playlistId,
           let path = HomeArtworkCache.playlistPath(id: playlistId),
           let str = await artworkURL(for: path),
           let url = URL(string: str) {
            return url
        }
        switch item.playTarget {
        case .artist(let name):
            if let path = await artistPortraitPath(for: name),
               let str = await artworkURL(for: path) {
                return URL(string: str)
            }
            if let path = try? await songs(page: 1, limit: 8, search: name, artist: name).items.first?.path,
               let str = await artworkURL(for: path) {
                return URL(string: str)
            }
        case .album(let name, let artist):
            if let path = try? await songs(page: 1, limit: 1, search: name, artist: artist, album: name).items.first?.path,
               let str = await artworkURL(for: path) {
                return URL(string: str)
            }
        default:
            break
        }
        return nil
    }

    /// Active household profile, unless caller passes an explicit member id.
    private func scopedMember(_ explicit: String?) -> String? {
        if let explicit = explicit?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank {
            return explicit
        }
        return ActiveProfileStore.activeMemberId()
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
