import Foundation
import WidgetKit

extension Notification.Name {
    static let localPlaybackDidChange = Notification.Name("com.bockmedia.localPlaybackDidChange")
    static let widgetSessionShouldRefresh = Notification.Name("com.bockmedia.widgetSessionShouldRefresh")
}

@MainActor
enum WidgetSessionBridge {
    private static var lastRecentRefresh: Date?
    private static let recentRefreshInterval: TimeInterval = 300

    /// Merges local phone playback with Alexa/Echo rows from the server.
    static func fetchNowPlayingItems(
        repository: BockMediaRepository,
        alexaDevices: [AlexaDevice]
    ) async -> (items: [NowPlayingDeviceItem], controlsAvailable: Bool, alexaDevices: [AlexaDevice]) {
        var devices = alexaDevices
        if devices.isEmpty {
            devices = (try? await repository.alexaRemoteDevices()) ?? []
        }
        let local = LocalPlaybackController.shared.nowPlayingDeviceItem()
        guard let np = try? await repository.nowPlayingDevices() else {
            if let local { return ([local], true, devices) }
            return ([], false, devices)
        }
        let items = NowPlayingMerge.devicesForMobile(
            remote: np.items,
            local: local,
            alexaDevices: devices
        )
        return (items, np.controlsAvailable, devices)
    }

    static func sync(repository: BockMediaRepository, alexaDevices: [AlexaDevice]) async {
        let result = await fetchNowPlayingItems(repository: repository, alexaDevices: alexaDevices)
        await update(repository: repository, items: result.items, controlsAvailable: result.controlsAvailable)
    }

    static func update(
        repository: BockMediaRepository,
        items: [NowPlayingDeviceItem],
        controlsAvailable: Bool = false
    ) async {
        guard WidgetSessionStore.isAvailable else { return }

        let baseURL = try? await repository.resolveBaseURL()
        let mapped = await mapItems(items, controlsAvailable: controlsAvailable, repository: repository)
        let existing = WidgetSessionStore.load()
        WidgetSessionStore.save(
            WidgetSessionSnapshot(
                baseURL: baseURL ?? existing?.baseURL,
                updatedAt: Date(),
                items: mapped,
                recentPlaylists: existing?.recentPlaylists ?? [],
                controlsAvailable: controlsAvailable
            )
        )
        WidgetCenter.shared.reloadTimelines(ofKind: "NowPlayingWidget")

        let shouldRefreshRecent = mapped.isEmpty
            || lastRecentRefresh == nil
            || Date().timeIntervalSince(lastRecentRefresh!) >= recentRefreshInterval
        guard shouldRefreshRecent else { return }
        lastRecentRefresh = Date()

        let recent = await loadRecentPlaylists(repository: repository)
        let current = WidgetSessionStore.load()
        WidgetSessionStore.save(
            WidgetSessionSnapshot(
                baseURL: baseURL ?? current?.baseURL ?? existing?.baseURL,
                updatedAt: Date(),
                items: current?.items ?? mapped,
                recentPlaylists: recent,
                controlsAvailable: controlsAvailable
            )
        )
        WidgetCenter.shared.reloadTimelines(ofKind: "NowPlayingWidget")
    }

    private static func mapItems(
        _ items: [NowPlayingDeviceItem],
        controlsAvailable: Bool,
        repository: BockMediaRepository
    ) async -> [WidgetNowPlayingItem] {
        var mapped: [WidgetNowPlayingItem] = []
        for dev in items {
            let isLocal = dev.deviceId == WidgetSnapshotLogic.localDeviceId
            var artURL: String?
            if let path = dev.filepath {
                artURL = await repository.artworkURL(for: path)
            }
            let canControl = isLocal
                || (controlsAvailable && !dev.deviceId.hasPrefix("msp-")
                    && !(dev.deviceName?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true))
            let widgetItem = WidgetNowPlayingItem(
                deviceId: dev.deviceId,
                deviceName: dev.deviceName,
                track: dev.track,
                artist: dev.artist,
                paused: dev.paused,
                isLocal: isLocal,
                canControl: canControl,
                artURL: artURL
            )
            mapped.append(widgetItem)
        }
        return WidgetSnapshotLogic.orderedForDisplay(mapped)
    }

    private static func loadRecentPlaylists(repository: BockMediaRepository) async -> [WidgetRecentPlaylist] {
        guard let dashboard = try? await repository.dashboardQuick() else { return [] }
        var seen = Set<String>()
        var candidates: [WidgetRecentPlaylist] = []

        for item in dashboard.recent {
            guard candidates.count < WidgetRecentPlaylistLimit.max else { break }
            if let name = item.playlist?.trimmingCharacters(in: .whitespaces), !name.isEmpty {
                let key = "pl-\(name.lowercased())"
                guard seen.insert(key).inserted else { continue }
                candidates.append(
                    WidgetRecentPlaylist(
                        id: key,
                        title: name,
                        subtitle: item.artist,
                        playlistId: nil,
                        playlistName: name,
                        songPath: nil
                    )
                )
                continue
            }
            guard let path = item.path?.trimmingCharacters(in: .whitespaces), !path.isEmpty,
                  let title = item.track?.trimmingCharacters(in: .whitespaces), !title.isEmpty else { continue }
            let key = "song-\(path)"
            guard seen.insert(key).inserted else { continue }
            candidates.append(
                WidgetRecentPlaylist(
                    id: key,
                    title: title,
                    subtitle: item.artist,
                    playlistId: nil,
                    playlistName: nil,
                    songPath: path
                )
            )
        }

        guard !candidates.isEmpty,
              candidates.contains(where: { $0.playlistName != nil }),
              let playlists = try? await repository.playlists(limit: 500) else {
            return candidates
        }
        let byName = Dictionary(
            playlists.items.map { ($0.name.lowercased(), $0.id) },
            uniquingKeysWith: { first, _ in first }
        )
        return candidates.map { entry in
            guard let name = entry.playlistName,
                  let id = byName[name.lowercased()] else { return entry }
            return WidgetRecentPlaylist(
                id: entry.id,
                title: entry.title,
                subtitle: entry.subtitle,
                playlistId: id,
                playlistName: name,
                songPath: entry.songPath
            )
        }
    }
}
