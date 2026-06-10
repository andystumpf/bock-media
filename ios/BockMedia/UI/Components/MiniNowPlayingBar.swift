import SwiftUI

@MainActor
final class MiniNowPlayingModel: ObservableObject {
    @Published var device: NowPlayingDeviceItem?
    @Published var controlsAvailable = false
    @Published var alexaDevices: [AlexaDevice] = []
    @Published var artURL: URL?
    @Published var isLocal = false

    private var pollTask: Task<Void, Never>?

    func start(repository: BockMediaRepository, remoteOk: Bool) {
        pollTask?.cancel()
        pollTask = Task {
            await refresh(repository: repository, remoteOk: remoteOk)
            while !Task.isCancelled {
                let interval: UInt64 = device != nil ? 3_000_000_000 : 5_000_000_000
                try? await Task.sleep(nanoseconds: interval)
                await refresh(repository: repository, remoteOk: remoteOk)
            }
        }
    }

    func refreshOnFocus(repository: BockMediaRepository, remoteOk: Bool) async {
        await refresh(repository: repository, remoteOk: remoteOk)
    }

    func stop() {
        pollTask?.cancel()
    }

    private func refresh(repository: BockMediaRepository, remoteOk: Bool) async {
        let result = await WidgetSessionBridge.fetchNowPlayingItems(
            repository: repository,
            alexaDevices: alexaDevices
        )
        alexaDevices = result.alexaDevices
        await WidgetSessionBridge.update(
            repository: repository,
            items: result.items,
            controlsAvailable: result.controlsAvailable
        )

        if let local = LocalPlaybackController.shared.nowPlayingDeviceItem() {
            isLocal = true
            device = local
            controlsAvailable = true
            if let path = local.filepath, let urlStr = await repository.artworkURL(for: path) {
                artURL = URL(string: urlStr)
            }
            _ = remoteOk
            return
        }
        isLocal = false
        controlsAvailable = result.controlsAvailable
        _ = PlaybackFocus.syncPendingFocus(items: result.items, alexaDevices: alexaDevices)
        device = PlaybackFocus.resolveFocusedItem(items: result.items, alexaDevices: alexaDevices)
            ?? result.items.first
        if let path = device?.filepath, let urlStr = await repository.artworkURL(for: path) {
            artURL = URL(string: urlStr)
        } else {
            artURL = nil
        }
        _ = remoteOk
    }

    var canControl: Bool {
        if isLocal { return true }
        guard let dev = device else { return false }
        return canControlDevice(dev, alexaDevices: alexaDevices, controlsAvailable: controlsAvailable, remoteOk: true)
    }
}

private func miniPlayerSubtitle(dev: NowPlayingDeviceItem, isLocal: Bool) -> String {
    if isLocal {
        if let artist = dev.artist?.trimmingCharacters(in: .whitespacesAndNewlines), !artist.isEmpty {
            return "This iPhone · \(artist)"
        }
        return "This iPhone"
    }
    let device = dev.deviceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if let artist = dev.artist?.trimmingCharacters(in: .whitespacesAndNewlines), !artist.isEmpty {
        return device.isEmpty ? artist : "\(device) · \(artist)"
    }
    return device.isEmpty ? "Speaker" : device
}

struct MiniNowPlayingBar: View {
    @ObservedObject var appState: AppState
    @ObservedObject var model: MiniNowPlayingModel
    let onOpen: () -> Void
    let onControl: (NowPlayingDeviceItem, String) async -> Void

    var body: some View {
        if let dev = model.device, dev.track != nil || dev.artist != nil {
            HStack(spacing: 10) {
                Button(action: onOpen) {
                    HStack(spacing: 10) {
                        BockArtwork(url: model.artURL, size: 40, cornerRadius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(dev.track ?? "Playing")
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)
                                .foregroundStyle(BockColors.onSurface)
                            Text(miniPlayerSubtitle(dev: dev, isLocal: model.isLocal))
                                .font(.caption)
                                .lineLimit(1)
                                .foregroundStyle(BockColors.muted)
                        }
                        Spacer(minLength: 0)
                    }
                }
                .buttonStyle(.plain)

                if model.canControl {
                    Button {
                        Task {
                            if model.isLocal {
                                LocalPlaybackController.shared.togglePlayPause()
                                await model.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
                            } else {
                                await onControl(dev, dev.paused ? "play" : "pause")
                            }
                        }
                    } label: {
                        BockIcon(icon: dev.paused ? .playArrow : .pause, size: 24)
                            .foregroundStyle(BockColors.onSurface)
                    }
                    Button {
                        Task {
                            if model.isLocal {
                                LocalPlaybackController.shared.skipNext()
                                await model.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
                            } else {
                                await onControl(dev, "next")
                            }
                        }
                    } label: {
                        BockIcon(icon: .skipNext, size: 24)
                            .foregroundStyle(BockColors.onSurface)
                    }
                    Button {
                        Task {
                            if model.isLocal {
                                LocalPlaybackController.shared.stopPlayback()
                                await model.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
                            } else {
                                await onControl(dev, "stop")
                            }
                        }
                    } label: {
                        BockIcon(icon: .stop, size: 22)
                            .foregroundStyle(BockColors.onSurface)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                LinearGradient(colors: [BockColors.miniBarTop, BockColors.black], startPoint: .top, endPoint: .bottom)
            )
        }
    }
}
