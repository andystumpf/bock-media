import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var isConnected: Bool?
    @Published var bootstrapMessage = "Connecting…"
    @Published var pendingPlayTarget: PlayTarget?
    @Published var remoteOk = false
    @Published var toast: String?
    @Published var accountRoute: AccountRoute?
    @Published var playbackFocusGeneration = 0
    @Published var pendingDeepLink: DeepLink?
    @Published var pendingControl: (deviceId: String, action: String)?
    @Published var showNowPlayingSheet = false

    let preferences = AppPreferences()
    let repository: BockMediaRepository

    init() {
        repository = BockMediaRepository(preferences: preferences)
        ClientPrefsSync.configure(repository: repository)
        OfflineDownloadManager.shared.configure(repository: repository, preferences: preferences)
        NowPlayingPollService.shared.configure(repository: repository)
    }

    func bootstrap() async {
        _ = ClientIdStore.clientId()
        preferences.applyBuildServerURLs()
        preferences.clearCredentialsIfNotRemembered()

        preferences.applyBuildDefaultsIfEmpty()
        bootstrapMessage = "Checking server…"

        let remember = preferences.rememberMe
        let wasConnected = preferences.hasConnectedBefore
        guard remember || wasConnected else {
            isConnected = false
            return
        }

        let result = await withTimeout(seconds: 12) {
            await self.repository.testConnection()
        }

        switch result {
        case .success(.success):
            preferences.setHasConnected(true)
            isConnected = true
            DeviceAnalyticsReporter.reportConnect(repository: repository)
            await refreshRemoteStatus()
            await ClientPrefsSync.pullAndApply(repository: repository)
        case .success(.failure), .timedOut:
            if wasConnected && (preferences.localServerURL != nil || preferences.externalServerURL != nil) {
                isConnected = true
            } else {
                isConnected = false
            }
        }
    }

    func connect(user: String, pass: String, token: String, rememberMe: Bool) async throws {
        preferences.rememberMe = rememberMe
        preferences.setCredentials(user: user, pass: pass, token: token)
        repository.invalidateAPI()
        switch await repository.testConnection() {
        case .success:
            preferences.setHasConnected(true)
            isConnected = true
            DeviceAnalyticsReporter.reportConnect(repository: repository)
            await refreshRemoteStatus()
            await ClientPrefsSync.pullAndApply(repository: repository)
        case .failure(let error):
            throw error
        }
    }

    func play(_ target: PlayTarget) {
        if PhonePlayback.canPlayLocally(target: target, remoteOk: remoteOk) {
            Task {
                await LocalPlaybackController.shared.playTarget(
                    repository: repository,
                    target: target,
                    shuffle: target.shuffleDefault
                )
                if LocalPlaybackController.shared.state.error == nil {
                    PlaybackFocus.notePlayStarted(LocalPlaybackIds.localPhoneDeviceId, label: "This iPhone")
                    playbackFocusGeneration = PlaybackFocus.generation
                    toast = "Playing \"\(target.label)\" on this iPhone"
                    showNowPlayingSheet = true
                } else if let err = LocalPlaybackController.shared.state.error {
                    toast = err
                }
            }
            return
        }
        pendingPlayTarget = target
    }

    func playFromWidget(
        playlistId: String?,
        playlistName: String?,
        songPath: String?,
        songTitle: String?
    ) async {
        if let playlistId, let playlistName {
            pendingPlayTarget = .playlist(id: playlistId, name: playlistName)
            return
        }
        if let playlistName {
            if let match = try? await repository.playlists(search: playlistName, limit: 20).items
                .first(where: { $0.name.localizedCaseInsensitiveCompare(playlistName) == .orderedSame }) {
                pendingPlayTarget = .playlist(id: match.id, name: match.name)
            } else {
                toast = "Could not find playlist “\(playlistName)”"
            }
            return
        }
        if let songPath, let songTitle {
            pendingPlayTarget = .song(path: songPath, title: songTitle)
        }
    }

    func playHomeCard(_ card: HomeCard) {
        HomeTileEngagement.recordSelection(cardId: card.id)
        ClientPrefsSync.schedulePush()
        // Quick-access cards always open the device picker (parity with Android),
        // rather than auto-playing on this iPhone.
        pendingPlayTarget = card.playTarget
    }

    func refreshRemoteStatus() async {
        if let status = try? await repository.alexaRemoteStatus() {
            remoteOk = alexaControlsAvailable(status)
        }
    }
}

private enum BootstrapTimeoutResult<T> {
    case success(T)
    case timedOut
}

@MainActor
private func withTimeout<T>(seconds: TimeInterval, operation: @escaping @Sendable () async -> T) async -> BootstrapTimeoutResult<T> {
    await withTaskGroup(of: BootstrapTimeoutResult<T>.self) { group in
        group.addTask { .success(await operation()) }
        group.addTask {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            return .timedOut
        }
        let first = await group.next()
        group.cancelAll()
        return first ?? .timedOut
    }
}
