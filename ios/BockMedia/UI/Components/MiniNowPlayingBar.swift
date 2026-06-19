import Combine
import SwiftUI

@MainActor
final class MiniNowPlayingModel: ObservableObject {
    @Published var device: NowPlayingDeviceItem?
    @Published var controlsAvailable = false
    @Published var alexaDevices: [AlexaDevice] = []
    @Published var artURL: URL?
    @Published var isLocal = false

    private let service = NowPlayingPollService.shared
    private var repository: BockMediaRepository?
    private var cancellables: Set<AnyCancellable> = []
    private var recomputeTask: Task<Void, Never>?
    private var artworkPath: String?
    private var subscribed = false

    func start(repository: BockMediaRepository, remoteOk: Bool) {
        self.repository = repository
        service.configure(repository: repository)
        if !subscribed {
            service.addSubscriber()
            subscribed = true
        }

        cancellables.removeAll()
        // Recompute whenever the shared poller publishes, or local playback changes.
        service.$items
            .combineLatest(service.$controlsAvailable, service.$alexaDevices)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.scheduleRecompute() } }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .localPlaybackDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.scheduleRecompute() } }
            .store(in: &cancellables)

        scheduleRecompute()
    }

    func refreshOnFocus(repository: BockMediaRepository, remoteOk: Bool) async {
        await service.refreshNow()
        scheduleRecompute()
    }

    func stop() {
        cancellables.removeAll()
        recomputeTask?.cancel()
        if subscribed {
            service.removeSubscriber()
            subscribed = false
        }
    }

    private func scheduleRecompute() {
        recomputeTask?.cancel()
        recomputeTask = Task { [weak self] in await self?.recompute() }
    }

    private func recompute() async {
        guard let repository else { return }
        alexaDevices = service.alexaDevices

        if let local = LocalPlaybackController.shared.nowPlayingDeviceItem() {
            isLocal = true
            device = local
            controlsAvailable = true
            await resolveArt(path: local.filepath, repository: repository)
            return
        }
        isLocal = false
        controlsAvailable = service.controlsAvailable
        let items = service.items
        _ = PlaybackFocus.syncPendingFocus(items: items, alexaDevices: alexaDevices)
        device = PlaybackFocus.resolveFocusedItem(items: items, alexaDevices: alexaDevices)
            ?? items.first
        await resolveArt(path: device?.filepath, repository: repository)
    }

    private func resolveArt(path: String?, repository: BockMediaRepository) async {
        guard let path else {
            artworkPath = nil
            artURL = nil
            return
        }
        if path == artworkPath, artURL != nil { return }
        artworkPath = path
        if let urlStr = await repository.artworkURL(for: path) {
            artURL = URL(string: urlStr)
        } else {
            artURL = nil
        }
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
