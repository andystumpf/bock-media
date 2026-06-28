import Combine
import SwiftUI

@MainActor
final class NowPlayingViewModel: ObservableObject {
    @Published var devices: [NowPlayingDeviceItem] = []
    @Published var selectedIndex = 0
    @Published var alexaDevices: [AlexaDevice] = []
    @Published var controlsAvailable = false
    @Published var remoteOk = false
    @Published var loading = true
    @Published var error: String?
    @Published var volumes: [String: Int] = [:]
    @Published var artURLs: [String: URL] = [:]

    private let service = NowPlayingPollService.shared
    private var repository: BockMediaRepository?
    private var cancellables: Set<AnyCancellable> = []
    private var applyTask: Task<Void, Never>?
    private var remoteOkCheckedAt: Date?
    private var subscribed = false
    private static let remoteStatusTTL: TimeInterval = 30

    var device: NowPlayingDeviceItem? {
        devices.indices.contains(selectedIndex) ? devices[selectedIndex] : devices.first
    }

    func start(repository: BockMediaRepository) {
        self.repository = repository
        service.configure(repository: repository)
        if !subscribed {
            service.addSubscriber()
            subscribed = true
        }

        cancellables.removeAll()
        service.$items
            .combineLatest(service.$controlsAvailable, service.$alexaDevices)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.scheduleApply() } }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: .localPlaybackDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.scheduleApply() } }
            .store(in: &cancellables)

        Task { await service.refreshNow() }
        scheduleApply()
    }

    func stop() {
        cancellables.removeAll()
        applyTask?.cancel()
        applyTask = nil
        if subscribed {
            service.removeSubscriber()
            subscribed = false
        }
    }

    func refresh(repository: BockMediaRepository) async {
        await service.refreshNow()
        await apply()
    }

    private func scheduleApply() {
        applyTask?.cancel()
        applyTask = Task { [weak self] in await self?.apply() }
    }

    private func apply() async {
        guard let repository else { return }
        controlsAvailable = service.controlsAvailable
        alexaDevices = service.alexaDevices
        await refreshRemoteOkIfStale(repository: repository)

        let local = LocalPlaybackController.shared.nowPlayingDeviceItem()
        devices = NowPlayingMerge.devicesForMobile(
            remote: service.items,
            local: local,
            alexaDevices: alexaDevices
        )
        if selectedIndex >= devices.count {
            selectedIndex = max(0, devices.count - 1)
        }
        let focusIdx = devices.firstIndex(where: { $0.deviceId == PlaybackFocus.focusedDeviceId })
        if let focusIdx, focusIdx != selectedIndex {
            selectedIndex = focusIdx
        }
        await resolveArtwork(repository: repository)
        await refreshVolumes(repository: repository)
        error = nil
        if service.lastUpdated != nil || LocalPlaybackController.shared.state.active {
            loading = false
        }
    }

    private func refreshRemoteOkIfStale(repository: BockMediaRepository) async {
        if let at = remoteOkCheckedAt, Date().timeIntervalSince(at) < Self.remoteStatusTTL { return }
        if let status = try? await repository.alexaRemoteStatus() {
            remoteOk = alexaControlsAvailable(status)
            remoteOkCheckedAt = Date()
        }
    }

    func control(repository: BockMediaRepository, action: String, device dev: NowPlayingDeviceItem) async {
        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
            switch action {
            case "play", "pause": LocalPlaybackController.shared.togglePlayPause()
            case "next": LocalPlaybackController.shared.skipNext()
            case "previous": LocalPlaybackController.shared.skipPrevious()
            case "stop": LocalPlaybackController.shared.stopPlayback()
            default: break
            }
            await refresh(repository: repository)
            return
        }
        guard let serial = resolveSerial(device: dev, alexaDevices: alexaDevices) else { return }
        do {
            _ = try await repository.deviceControl(
                deviceId: dev.deviceId,
                deviceName: dev.deviceName ?? "",
                serial: serial,
                action: action
            )
            await refresh(repository: repository)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func setVolume(repository: BockMediaRepository, device dev: NowPlayingDeviceItem, _ value: Int) async {
        guard dev.deviceId != LocalPlaybackIds.localPhoneDeviceId,
              let serial = resolveSerial(device: dev, alexaDevices: alexaDevices) else { return }
        volumes[dev.deviceId] = value
        try? await repository.setVolume(serial: serial, deviceName: dev.deviceName ?? "", volume: value)
    }

    func volume(for deviceId: String) -> Int {
        volumes[deviceId] ?? 50
    }

    func progress(for dev: NowPlayingDeviceItem) -> NowPlayingProgress {
        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
            let s = LocalPlaybackController.shared.state
            return NowPlayingProgress(elapsedMs: s.positionMs, durationMs: s.durationMs)
        }
        return computeNowPlayingProgress(
            timestamp: dev.timestamp,
            durationMs: dev.duration_ms,
            offsetMs: dev.offset_ms,
            paused: dev.paused
        )
    }

    func canControl(_ dev: NowPlayingDeviceItem) -> Bool {
        canControlDevice(dev, alexaDevices: alexaDevices, controlsAvailable: controlsAvailable, remoteOk: remoteOk)
    }

    private func refreshVolumes(repository: BockMediaRepository) async {
        for dev in devices {
            guard canControl(dev),
                  dev.deviceId != LocalPlaybackIds.localPhoneDeviceId,
                  volumes[dev.deviceId] == nil,
                  let serial = resolveSerial(device: dev, alexaDevices: alexaDevices),
                  let v = try? await repository.getVolume(serial: serial) else { continue }
            volumes[dev.deviceId] = v
        }
    }

    private func resolveArtwork(repository: BockMediaRepository) async {
        var urls: [String: URL] = [:]
        for dev in devices {
            if let path = dev.filepath, let urlStr = await repository.artworkURL(for: path), let url = URL(string: urlStr) {
                urls[dev.deviceId] = url
            }
        }
        artURLs = urls
    }
}

struct NowPlayingView: View {
    @ObservedObject var appState: AppState
    @StateObject private var viewModel = NowPlayingViewModel()
    @ObservedObject private var localPlayback = LocalPlaybackController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var scrollPage: Int?
    @State private var showSleep = false
    @State private var showUpNext = false
    @State private var showRoomRequests = false
    @State private var showAddToRoom = false
    @State private var showHistory = false
    @State private var sheetDeviceId: String?

    private var sheetDevice: NowPlayingDeviceItem? {
        guard let id = sheetDeviceId else { return viewModel.device }
        return viewModel.devices.first(where: { $0.deviceId == id }) ?? viewModel.device
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if viewModel.loading && viewModel.devices.isEmpty {
                LoadingBox()
            } else if viewModel.devices.isEmpty {
                emptyState
            } else {
                devicePager
            }

            if let error = viewModel.error {
                VStack {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(8)
                        .background(.black.opacity(0.6))
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .padding()
                    Spacer()
                }
            }
        }
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Close") { dismiss() }
                    .foregroundStyle(.white)
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        sheetDeviceId = viewModel.device?.deviceId
                        showUpNext = true
                    } label: { Label("Up next", icon: .musicNote) }
                    Button {
                        sheetDeviceId = viewModel.device?.deviceId
                        showSleep = true
                    } label: { Label("Sleep timer", icon: .bedtime) }
                    Button { showHistory = true } label: { Label("Recently played", icon: .history) }
                    if let dev = viewModel.device, viewModel.canControl(dev) {
                        Button(role: .destructive) {
                            Task { await viewModel.control(repository: appState.repository, action: "stop", device: dev) }
                        } label: { Label("Stop playback", icon: .stop) }
                    }
                    if let dev = viewModel.device, let path = dev.filepath {
                        Button {
                            Task {
                                try? await appState.repository.addFavorite(
                                    path: path, title: dev.track, artist: dev.artist, album: dev.album
                                )
                                appState.toast = "Added to favorites"
                            }
                        } label: { Label("Add to favorites", icon: .favorite) }
                        Button(role: .destructive) {
                            Task {
                                try? await appState.repository.addIgnored(path: path)
                                appState.toast = "Track ignored"
                            }
                        } label: { Label("Never play again", icon: .block) }
                    }
                } label: {
                    BockIcon(icon: .moreHoriz, size: 22)
                        .foregroundStyle(.white)
                }
            }
        }
        .sheet(isPresented: $showSleep) {
            if let dev = sheetDevice {
                SleepTimerSheet(
                    deviceName: dev.deviceName ?? dev.deviceId,
                    hasSleep: dev.sleep != nil,
                    onSetMinutes: { min in
                        try? await appState.repository.setSleep(deviceId: dev.deviceId, minutes: min)
                        appState.toast = "Sleeping in \(min) min"
                        await viewModel.refresh(repository: appState.repository)
                    },
                    onSetSongs: { songs in
                        try? await appState.repository.setSleep(deviceId: dev.deviceId, songs: songs)
                        appState.toast = songs == 1 ? "Stopping after this song" : "Stopping after \(songs) songs"
                        await viewModel.refresh(repository: appState.repository)
                    },
                    onCancel: {
                        try? await appState.repository.setSleep(deviceId: dev.deviceId)
                        appState.toast = "Sleep timer cancelled"
                        await viewModel.refresh(repository: appState.repository)
                    },
                    onDismiss: { showSleep = false }
                )
            }
        }
        .sheet(isPresented: $showUpNext) {
            if let dev = sheetDevice {
                UpNextSheet(
                    repository: appState.repository,
                    tracks: upNextTracks(for: dev),
                    isLocal: dev.deviceId == LocalPlaybackIds.localPhoneDeviceId,
                    onPlayAtIndex: { idx in
                        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
                            LocalPlaybackController.shared.playAtIndex(localPlayback.state.index + 1 + idx)
                        }
                        showUpNext = false
                    },
                    onDismiss: { showUpNext = false }
                )
            }
        }
        .sheet(isPresented: $showRoomRequests) {
            if let dev = sheetDevice {
                RoomRequestsSheet(
                    appState: appState,
                    deviceId: dev.deviceId,
                    deviceName: dev.deviceName,
                    requests: dev.upNext,
                    onUpdated: {
                        await viewModel.refresh(repository: appState.repository)
                        return viewModel.devices.first(where: { $0.deviceId == dev.deviceId })?.upNext ?? []
                    },
                    onDismiss: { showRoomRequests = false }
                )
            }
        }
        .sheet(isPresented: $showAddToRoom) {
            if let dev = sheetDevice, let path = dev.filepath {
                AddToRoomSheet(
                    repository: appState.repository,
                    path: path,
                    track: dev.track ?? "Track",
                    artist: dev.artist,
                    remoteOk: appState.remoteOk,
                    onDismiss: { showAddToRoom = false },
                    onDone: { msg in
                        appState.toast = msg
                        showAddToRoom = false
                    }
                )
            }
        }
        .sheet(isPresented: $showHistory) {
            StreamHistorySheet(appState: appState, onDismiss: { showHistory = false })
        }
        .onAppear {
            scrollPage = viewModel.selectedIndex
            viewModel.start(repository: appState.repository)
        }
        .onDisappear { viewModel.stop() }
        .onChange(of: scrollPage) { _, page in
            guard let page, page != viewModel.selectedIndex else { return }
            viewModel.selectedIndex = page
        }
        .onChange(of: viewModel.selectedIndex) { _, idx in
            guard scrollPage != idx else { return }
            scrollPage = idx
        }
        .onChange(of: appState.playbackFocusGeneration) { _, _ in
            Task { await viewModel.refresh(repository: appState.repository) }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            BockIcon(icon: .musicNote, size: 72)
                .foregroundStyle(.white.opacity(0.45))
            Text("Nothing is currently playing")
                .font(.title2.bold())
                .foregroundStyle(.white)
            Text("Ask Alexa to play a playlist, artist, or album")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.65))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    private var devicePager: some View {
        ZStack(alignment: .bottom) {
            ScrollView(.vertical) {
                LazyVStack(spacing: 0) {
                    ForEach(Array(viewModel.devices.enumerated()), id: \.element.id) { idx, dev in
                        NowPlayingDevicePage(
                            dev: displayDevice(dev),
                            initialArtURL: viewModel.artURLs[dev.deviceId],
                            viewModel: viewModel,
                            repository: appState.repository,
                            remoteOk: viewModel.remoteOk,
                            showPagerInset: viewModel.devices.count > 1,
                            onUpNext: {
                                sheetDeviceId = dev.deviceId
                                showUpNext = true
                            },
                            upNextCount: upNextTracks(for: dev).count,
                            onRoomQueue: {
                                sheetDeviceId = dev.deviceId
                                showRoomRequests = true
                            },
                            onAddToRoom: {
                                sheetDeviceId = dev.deviceId
                                showAddToRoom = true
                            }
                        )
                        .containerRelativeFrame(.vertical)
                        .id(idx)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $scrollPage)
            .scrollIndicators(.hidden)

            if viewModel.devices.count > 1 {
                DevicePagerDots(current: scrollPage ?? viewModel.selectedIndex, total: viewModel.devices.count)
                    .padding(.bottom, 12)
            }
        }
    }

    private func displayDevice(_ dev: NowPlayingDeviceItem) -> NowPlayingDeviceItem {
        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
            return localPlayback.nowPlayingDeviceItem() ?? dev
        }
        return dev
    }

    private func upNextTracks(for dev: NowPlayingDeviceItem) -> [UpcomingTrack] {
        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
            let state = localPlayback.state
            return state.tracks.dropFirst(state.index + 1).map {
                UpcomingTrack(title: $0.title, artist: $0.artist, path: $0.path)
            }
        }
        return dev.upcoming
    }
}

private struct NowPlayingDevicePage: View {
    let dev: NowPlayingDeviceItem
    let initialArtURL: URL?
    @ObservedObject var viewModel: NowPlayingViewModel
    let repository: BockMediaRepository
    var remoteOk: Bool = false
    var showPagerInset: Bool
    let onUpNext: () -> Void
    let upNextCount: Int
    var onRoomQueue: (() -> Void)? = nil
    var onAddToRoom: (() -> Void)? = nil

    @ObservedObject private var localPlayback = LocalPlaybackController.shared
    @State private var artURL: URL?
    @State private var artPath: String = ""
    @State private var showLyrics = false
    @State private var lyricsOffsetMs = 0
    @State private var lyrics: LyricsResponse?
    @State private var lyricsLoading = false
    @State private var lyricsError: String?

    var body: some View {
        let progress = viewModel.progress(for: dev)
        let canControl = viewModel.canControl(dev)
        let hasTrack = dev.filepath?.isEmpty == false

        ZStack {
            NowPlayingArtBackdrop(url: artURL)

            VStack(spacing: 0) {
                Spacer(minLength: 8)

                ZStack(alignment: .topTrailing) {
                    Group {
                        if showLyrics, hasTrack {
                            TimelineView(.animation(minimumInterval: 0.2, paused: !showLyrics)) { _ in
                                LyricsPanel(
                                    lyrics: lyrics,
                                    loading: lyricsLoading,
                                    error: lyricsError,
                                    positionMs: progress.elapsedMs,
                                    offsetMs: lyricsOffsetMs,
                                    onOffsetChange: { lyricsOffsetMs = $0 }
                                )
                            }
                            .frame(width: 320, height: 320)
                        } else {
                            BockArtwork(url: artURL, size: 280, cornerRadius: 8)
                                .shadow(color: .black.opacity(0.4), radius: 24, y: 12)
                        }
                    }
                    if hasTrack {
                        Button {
                            if showLyrics {
                                showLyrics = false
                            } else {
                                showLyrics = true
                                if lyrics == nil || ((lyrics?.lines.isEmpty ?? true) && (lyrics?.plain.isEmpty ?? true)) {
                                    lyricsError = "No lyrics found for this track"
                                }
                            }
                        } label: {
                            Label(showLyrics ? "Cover" : "Lyrics", systemImage: showLyrics ? "square.stack" : "text.quote")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(Color.black.opacity(0.55))
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .padding(8)
                    }
                }

                Spacer(minLength: 16)

                VStack(spacing: 0) {
                    LinearGradient(
                        colors: [.clear, .black.opacity(0.45), .black.opacity(0.82), .black.opacity(0.95)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 48)
                    .allowsHitTesting(false)

                    VStack(spacing: 16) {
                        VStack(spacing: 6) {
                            Text(dev.track ?? "Not playing")
                                .font(.title2.bold())
                                .foregroundStyle(.white)
                                .multilineTextAlignment(.center)
                                .lineLimit(2)
                            Text([dev.artist, dev.album].compactMap { $0 }.joined(separator: " · "))
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.65))
                                .multilineTextAlignment(.center)
                                .lineLimit(2)
                            Text(dev.deviceName ?? dev.deviceId)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.5))
                            if let sleep = dev.sleep {
                                let label = sleep.type == "time"
                                    ? "Sleep \(sleep.remainingMin ?? 0)m"
                                    : "\(sleep.remaining ?? 0) songs left"
                                Text(label)
                                    .font(.caption2)
                                    .foregroundStyle(BockColors.green)
                            }
                        }
                        .padding(.horizontal, 24)

                        if !dev.upNext.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Household requests")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.white.opacity(0.55))
                                ForEach(dev.upNext.prefix(4)) { req in
                                    let who = req.byMemberName ?? "Someone"
                                    let pending = req.status == "queued" ? " · pending" : ""
                                    Text("\(req.track ?? "Track") · \(who)\(pending)")
                                        .font(.caption)
                                        .foregroundStyle(.white.opacity(0.65))
                                        .lineLimit(1)
                                }
                                Button("Manage queue", action: { onRoomQueue?() })
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(BockColors.green)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 24)
                        }

                        if upNextCount > 0 {
                            Button("Up next · \(upNextCount) tracks", action: onUpNext)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.65))
                        }
                        if dev.filepath != nil, remoteOk, dev.deviceId != LocalPlaybackIds.localPhoneDeviceId {
                            Button("Add to another room") { onAddToRoom?() }
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.65))
                        }

                        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
                            LocalSeekBar()
                                .padding(.horizontal, 20)
                        } else if dev.duration_ms > 0 || progress.durationMs > 0 {
                            let dur = max(dev.duration_ms, progress.durationMs)
                            VStack(spacing: 4) {
                                ProgressView(value: progress.fraction)
                                    .tint(BockColors.green)
                                HStack {
                                    Text(formatPlaybackTime(seconds: progress.elapsedMs / 1000))
                                    Spacer()
                                    Text(formatPlaybackTime(seconds: dur / 1000))
                                }
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.55))
                            }
                            .padding(.horizontal, 20)
                        }

                        if canControl {
                            HStack(spacing: 40) {
                                Button {
                                    Task { await viewModel.control(repository: repository, action: "previous", device: dev) }
                                } label: {
                                    BockIcon(icon: .skipPrevious, size: 32)
                                }
                                Button {
                                    Task {
                                        await viewModel.control(
                                            repository: repository,
                                            action: dev.paused ? "play" : "pause",
                                            device: dev
                                        )
                                    }
                                } label: {
                                    BockIcon(icon: dev.paused ? .playArrow : .pause, size: 44)
                                }
                                Button {
                                    Task { await viewModel.control(repository: repository, action: "next", device: dev) }
                                } label: {
                                    BockIcon(icon: .skipNext, size: 32)
                                }
                            }
                            .foregroundStyle(.white)

                            Button {
                                Task { await viewModel.control(repository: repository, action: "stop", device: dev) }
                            } label: {
                                Label("Stop", icon: .stop)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 10)
                                    .background(Color.white.opacity(0.12))
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)

                            if dev.deviceId != LocalPlaybackIds.localPhoneDeviceId {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("Volume")
                                        .font(.caption)
                                        .foregroundStyle(.white.opacity(0.55))
                                    Slider(
                                        value: Binding(
                                            get: { Double(viewModel.volume(for: dev.deviceId)) },
                                            set: { viewModel.volumes[dev.deviceId] = Int($0) }
                                        ),
                                        in: 0...100,
                                        onEditingChanged: { editing in
                                            if !editing {
                                                Task {
                                                    await viewModel.setVolume(
                                                        repository: repository,
                                                        device: dev,
                                                        viewModel.volume(for: dev.deviceId)
                                                    )
                                                }
                                            }
                                        }
                                    )
                                    .tint(BockColors.green)
                                }
                                .padding(.horizontal, 24)
                            }
                        }
                    }
                    .padding(.bottom, showPagerInset ? 36 : 16)
                    .background(Color.black.opacity(0.95))
                }
            }
        }
        .task(id: dev.filepath ?? "") {
            showLyrics = false
            lyricsOffsetMs = 0
            lyricsError = nil
            guard let path = dev.filepath, !path.isEmpty else {
                lyrics = nil
                return
            }
            lyricsLoading = true
            let durSec = Int(progress.durationMs / 1000)
            lyrics = await repository.lyrics(
                path: path,
                durationSec: durSec > 0 ? durSec : nil,
                title: dev.track,
                artist: dev.artist,
                album: dev.album
            )
            lyricsLoading = false
        }
        // Resolve artwork from the *live* track so it never lags a song behind the
        // title/metadata (which update instantly from LocalPlaybackController).
        .task(id: dev.filepath) {
            let path = dev.filepath ?? ""
            if path != artPath {
                artURL = nil
                artPath = path
            }
            if path.isEmpty {
                artURL = nil
                return
            }
            if let str = await repository.artworkURL(for: path), let url = URL(string: str) {
                guard dev.filepath == path else { return }
                artURL = url
            } else {
                artURL = initialArtURL
            }
        }
    }
}

/// Interactive scrubber for local phone playback — drag to seek to any point.
private struct LocalSeekBar: View {
    @ObservedObject private var controller = LocalPlaybackController.shared
    @State private var dragging = false
    @State private var dragFraction: Double = 0

    var body: some View {
        let durationMs = controller.state.durationMs
        let positionMs = controller.state.positionMs
        let liveFraction = durationMs > 0
            ? min(max(Double(positionMs) / Double(durationMs), 0), 1)
            : 0
        let fraction = dragging ? dragFraction : liveFraction
        let shownMs = dragging ? Int64(dragFraction * Double(durationMs)) : positionMs

        VStack(spacing: 4) {
            Slider(
                value: Binding(
                    get: { fraction },
                    set: { dragging = true; dragFraction = $0 }
                ),
                in: 0...1,
                onEditingChanged: { editing in
                    if !editing {
                        if durationMs > 0 {
                            controller.seek(toSeconds: dragFraction * Double(durationMs) / 1000)
                        }
                        dragging = false
                    }
                }
            )
            .tint(BockColors.green)
            .disabled(durationMs <= 0)

            HStack {
                Text(formatPlaybackTime(seconds: shownMs / 1000))
                Spacer()
                Text(formatPlaybackTime(seconds: durationMs / 1000))
            }
            .font(.caption)
            .foregroundStyle(.white.opacity(0.55))
        }
    }
}

private struct NowPlayingArtBackdrop: View {
    let url: URL?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .scaleEffect(1.25)
                    .blur(radius: 56)
            } else {
                Color(white: 0.1)
            }
            LinearGradient(
                colors: [.black.opacity(0.25), .black.opacity(0.55), .black.opacity(0.88)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .ignoresSafeArea()
        .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            if let cached = ArtworkImageCache.memoryImage(for: url) {
                image = cached
                return
            }
            image = await ArtworkImageCache.load(url)
        }
    }
}

private struct DevicePagerDots: View {
    let current: Int
    let total: Int

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<total, id: \.self) { idx in
                Circle()
                    .fill(idx == current ? Color.white : Color.white.opacity(0.35))
                    .frame(width: idx == current ? 7 : 5, height: idx == current ? 7 : 5)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.black.opacity(0.35))
        .clipShape(Capsule())
    }
}
