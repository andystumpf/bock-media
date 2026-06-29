import SwiftUI

struct PlayLauncherModifier: ViewModifier {
    @ObservedObject var appState: AppState

    func body(content: Content) -> some View {
        content
            .sheet(item: $appState.pendingPlayTarget) { target in
                DevicePickerSheet(
                    repository: appState.repository,
                    playLabel: target.label,
                    playTarget: target,
                    remoteOk: appState.remoteOk,
                    shuffleDefault: target.shuffleDefault,
                    onDismiss: { appState.pendingPlayTarget = nil },
                    onPlay: { device, shuffle, label in
                        do {
                            let response = try await repositoryPlay(
                                repository: appState.repository,
                                target: target,
                                device: device,
                                shuffle: shuffle
                            )
                            if response.ok {
                                PlaybackFocus.notePlayStarted(device, label: response.device ?? label)
                                appState.playbackFocusGeneration = PlaybackFocus.generation
                                appState.toast = "Playing \"\(target.label)\" on \(response.device ?? label ?? device)"
                                appState.pendingPlayTarget = nil
                                appState.showNowPlayingSheet = true
                            } else {
                                appState.toast = response.error ?? "Play failed"
                            }
                        } catch {
                            appState.toast = error.localizedDescription
                        }
                    },
                    onPlayOnPhone: { shuffle in
                        await LocalPlaybackController.shared.playTarget(
                            repository: appState.repository,
                            target: target,
                            shuffle: shuffle
                        )
                        let err = LocalPlaybackController.shared.state.error
                        if let err {
                            appState.toast = err
                        } else {
                            PlaybackFocus.notePlayStarted(LocalPlaybackIds.localPhoneDeviceId, label: "This iPhone")
                            appState.playbackFocusGeneration = PlaybackFocus.generation
                            appState.toast = "Playing \"\(target.label)\" on this iPhone"
                            appState.pendingPlayTarget = nil
                            appState.showNowPlayingSheet = true
                        }
                    },
                    onPlayError: { error in
                        appState.toast = error.localizedDescription
                    }
                )
            }
    }
}

extension View {
    func playLauncher(appState: AppState) -> some View {
        modifier(PlayLauncherModifier(appState: appState))
    }
}
