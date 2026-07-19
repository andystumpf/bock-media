import AVFoundation
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
        do {
            let status = try await repository.alexaRemoteStatus()
            remoteOk = alexaControlsAvailable(status)
        } catch {
            remoteOk = false
        }
        remoteOkCheckedAt = Date()
    }

    func control(repository: BockMediaRepository, action: String, device dev: NowPlayingDeviceItem) async {
        if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
            switch action {
            case "play", "pause": LocalPlaybackController.shared.togglePlayPause()
            case "next": LocalPlaybackController.shared.skipNext()
            case "previous": LocalPlaybackController.shared.skipPrevious()
            case "stop": LocalPlaybackController.shared.stopPlayback()
            case "shuffle_on": LocalPlaybackController.shared.setShuffle(true)
            case "shuffle_off": LocalPlaybackController.shared.setShuffle(false)
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
        nowPlayingWithLifecycle
    }

    private var nowPlayingWithLifecycle: some View {
        nowPlayingWithSheets
            .accessibilityIdentifier(BockTestTags.nowPlayingBody)
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

    private var nowPlayingWithSheets: some View {
        nowPlayingCore
            .sheet(isPresented: $showSleep) { sleepSheetContent }
            .sheet(isPresented: $showUpNext) { upNextSheetContent }
            .sheet(isPresented: $showRoomRequests) { roomRequestsSheetContent }
            .sheet(isPresented: $showAddToRoom) { addToRoomSheetContent }
            .sheet(isPresented: $showHistory) { historySheetContent }
    }

    private var nowPlayingCore: some View {
        nowPlayingStack
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: String.self) { name in
                ArtistDetailView(appState: appState, artistName: name)
            }
            .navigationDestination(for: SearchRoute.self) { route in
                switch route {
                case .album(let name, let artist):
                    AlbumDetailView(appState: appState, albumName: name, artist: artist)
                case .genre(let name):
                    GenreDetailView(appState: appState, genreName: name)
                case .artist(let name):
                    ArtistDetailView(appState: appState, artistName: name)
                case .releaseRadar:
                    ReleaseRadarView(appState: appState)
                case .sonicAdventure:
                    SearchSonicAdventureView(appState: appState)
                }
            }
    }

    @ViewBuilder
    private var sleepSheetContent: some View {
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

    @ViewBuilder
    private var upNextSheetContent: some View {
        if let dev = sheetDevice {
            UpNextSheet(
                repository: appState.repository,
                tracks: upNextTracks(for: dev),
                isLocal: dev.deviceId == LocalPlaybackIds.localPhoneDeviceId,
                onPlayAtIndex: { idx in
                    if dev.deviceId == LocalPlaybackIds.localPhoneDeviceId {
                        LocalPlaybackController.shared.playAtIndex(localPlayback.state.index + 1 + idx)
                        showUpNext = false
                    } else {
                        Task {
                            guard let alexa = try? await appState.repository.alexaRemoteDevices(),
                                  let serial = resolveSerial(device: dev, alexaDevices: alexa) else {
                                viewModel.error = "Cannot control this speaker"
                                return
                            }
                            _ = try? await appState.repository.seekQueueIndex(
                                deviceId: dev.deviceId,
                                deviceName: dev.deviceName ?? "",
                                serial: serial,
                                relativeIndex: idx
                            )
                            await viewModel.refresh(repository: appState.repository)
                            showUpNext = false
                        }
                    }
                },
                onDismiss: { showUpNext = false }
            )
        }
    }

    @ViewBuilder
    private var roomRequestsSheetContent: some View {
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

    @ViewBuilder
    private var addToRoomSheetContent: some View {
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

    private var historySheetContent: some View {
        StreamHistorySheet(appState: appState, onDismiss: { showHistory = false })
    }

    @ViewBuilder
    private var nowPlayingStack: some View {
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
    }

    @ToolbarContentBuilder
    private var nowPlayingToolbar: some ToolbarContent {
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
                            appState: appState,
                            repository: appState.repository,
                            remoteOk: viewModel.remoteOk,
                            showPagerInset: viewModel.devices.count > 1,
                            upNextTracks: upNextTracks(for: dev),
                            onUpNext: {
                                sheetDeviceId = dev.deviceId
                                showUpNext = true
                            },
                            onHistory: { showHistory = true },
                            onSleep: {
                                sheetDeviceId = dev.deviceId
                                showSleep = true
                            },
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
                        .frame(maxWidth: .infinity)
                        .id(idx)
                    }
                }
                .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $scrollPage)
            .scrollIndicators(.hidden)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if viewModel.devices.count > 1 {
                DevicePagerDots(current: scrollPage ?? viewModel.selectedIndex, total: viewModel.devices.count)
                    .padding(.bottom, 12)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

private struct NowPlayingSpotifyTopBar: View {
    let deviceName: String
    let isLocal: Bool
    let playContext: String?
    let onDismiss: () -> Void
    let onHistory: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onDismiss) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("nowPlayingDismiss")

            VStack(spacing: 2) {
                Text(deviceName)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.65))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Button(action: onHistory) {
                BockIcon(icon: .history, size: 22)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
    }

    private var subtitle: String {
        if isLocal {
            if let playContext, !playContext.isEmpty { return playContext }
            return "This phone"
        }
        return "Alexa"
    }
}

private struct NowPlayingDevicePage: View {
    let dev: NowPlayingDeviceItem
    let initialArtURL: URL?
    @ObservedObject var viewModel: NowPlayingViewModel
    @ObservedObject var appState: AppState
    let repository: BockMediaRepository
    var remoteOk: Bool = false
    var showPagerInset: Bool
    let upNextTracks: [UpcomingTrack]
    let onUpNext: () -> Void
    let onHistory: () -> Void
    let onSleep: () -> Void
    var onRoomQueue: (() -> Void)? = nil
    var onAddToRoom: (() -> Void)? = nil

    @ObservedObject private var localPlayback = LocalPlaybackController.shared
    @Environment(\.dismiss) private var dismiss
    @State private var artURL: URL?
    @State private var artPath: String = ""
    @State private var showLyrics = false
    @State private var lyricsOffsetMs = 0
    @State private var lyrics: LyricsResponse?
    @State private var lyricsLoading = false
    @State private var lyricsError: String?
    @State private var videoId: String?
    @State private var videoPlayUrl: URL?
    @State private var videoStreamError: String?
    @State private var videoLoading = false
    @State private var prefetchedVideoId: String?
    @State private var prefetchedPlayUrl: URL?
    @State private var prefetchedStreamError: String?
    @State private var prefetchTrackKey = ""
    @State private var prefetchInProgress = false
    @State private var activeVideoTrackKey = ""
    @State private var preloadedPlayer: AVPlayer?
    @State private var preloadedPlayUrlString: String?
    @State private var videoPositionMs: Int64 = 0
    @State private var releaseYear: Int?
    @ObservedObject private var network = OfflineDownloadNetwork.shared

    private var showVideo: Bool { appState.preferences.nowPlayingVideo }
    private var hasTrack: Bool { dev.filepath?.isEmpty == false }
    private var hasTitle: Bool { !(dev.track?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }

    var body: some View {
        let progress = viewModel.progress(for: dev)
        let canControl = viewModel.canControl(dev)
        let isLocalPhone = dev.deviceId == LocalPlaybackIds.localPhoneDeviceId
        let shuffleActive = isLocalPhone ? localPlayback.state.shuffle : dev.shuffle
        let loopActive = isLocalPhone ? false : dev.loop
        let playContext = isLocalPhone ? (dev.sourceLabel ?? dev.playlist) : nil
        let trackPlaying = isLocalPhone ? localPlayback.state.isPlaying : !dev.paused

        GeometryReader { geo in
            let safeTop = geo.safeAreaInsets.top
            let safeBottom = geo.safeAreaInsets.bottom

            ZStack {
                NowPlayingArtBackdrop(url: artURL)
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
                    .opacity(showVideo ? 0 : 1)
                    .allowsHitTesting(false)

                Group {
                    if showVideo,
                       let activeVideoId = videoId ?? prefetchedVideoId,
                       videoPlayUrl != nil || videoLoading {
                        MusicVideoPlayerView(
                            playUrl: videoPlayUrl,
                            videoId: activeVideoId,
                            artURL: artURL,
                            loading: videoLoading,
                            preferences: appState.preferences,
                            playing: trackPlaying,
                            positionMs: videoPositionMs,
                            preloadedPlayer: {
                                guard let playUrl = videoPlayUrl,
                                      playUrl.absoluteString == preloadedPlayUrlString else { return nil }
                                return preloadedPlayer
                            }()
                        )
                    } else if showVideo {
                        NowPlayingArtBackdrop(url: artURL)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .opacity(showVideo ? 1 : 0)
                .allowsHitTesting(false)

                VStack {
                    Spacer()
                    NowPlayingBottomFade(deep: true)
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .allowsHitTesting(false)

                centerAlbumHero(progress: progress, safeTop: safeTop)
                    .opacity(showVideo ? 0 : 1)
                    .allowsHitTesting(!showVideo && showLyrics)

                nowPlayingFixedChrome(
                    progress: progress,
                    canControl: canControl,
                    isLocalPhone: isLocalPhone,
                    shuffleActive: shuffleActive,
                    loopActive: loopActive,
                    playContext: playContext,
                    safeTop: safeTop,
                    safeBottom: safeBottom
                )
                .zIndex(10)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .animation(nil, value: showVideo)
        }
        .ignoresSafeArea()
        .background(Color.black)
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
        .task(id: "\(showVideo)|\(dev.track ?? "")|\(dev.artist ?? "")|\(network.isOnWifi)") {
            preloadedPlayer?.pause()
            preloadedPlayer = nil
            preloadedPlayUrlString = nil
            prefetchedVideoId = nil
            prefetchedPlayUrl = nil
            prefetchedStreamError = nil
            prefetchTrackKey = ""
            prefetchInProgress = false
            guard showVideo else { return }
            let title = dev.track?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !title.isEmpty else { return }
            let trackKey = musicVideoTrackKey(title: title, artist: dev.artist)
            if !activeVideoTrackKey.isEmpty, trackKey != activeVideoTrackKey {
                videoId = nil
                videoPlayUrl = nil
                videoStreamError = nil
                videoLoading = true
                activeVideoTrackKey = ""
            }
            prefetchTrackKey = trackKey
            prefetchInProgress = true
            guard let base = try? await repository.resolveBaseURL() else {
                prefetchInProgress = false
                return
            }
            let lowBandwidth = !OfflineDownloadNetwork.shared.isOnWifi
            let durSec = Int(progress.durationMs / 1000)
            let prepared = await repository.prepareMusicVideoForTrack(
                title: title,
                artist: dev.artist,
                durationSec: durSec > 0 ? durSec : nil,
                baseUrl: base,
                lowBandwidth: lowBandwidth
            )
            guard prefetchTrackKey == trackKey else { return }
            prefetchedVideoId = prepared.videoId
            prefetchedPlayUrl = prepared.playUrl
            prefetchedStreamError = prepared.error
            prefetchInProgress = false
            if let url = prepared.playUrl {
                preloadedPlayer?.pause()
                preloadedPlayer = buildMusicVideoPlayer(url: url, preferences: appState.preferences)
                preloadedPlayUrlString = url.absoluteString
            }
        }
        .task(id: "\(showVideo)|\(dev.track ?? "")|\(dev.artist ?? "")|\(network.isOnWifi)|\(prefetchTrackKey)|\(prefetchedVideoId ?? "")|\(prefetchedPlayUrl?.absoluteString ?? "")|\(prefetchedStreamError ?? "")|\(prefetchInProgress)") {
            guard showVideo else {
                videoLoading = false
                return
            }
            if UITestSupport.isEnabled {
                let title = dev.track?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !title.isEmpty else {
                    videoLoading = false
                    return
                }
                videoId = "Wxu02T2Jwcc"
                videoLoading = false
                return
            }
            let title = dev.track?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !title.isEmpty else {
                videoLoading = false
                return
            }
            let trackKey = musicVideoTrackKey(title: title, artist: dev.artist)

            if prefetchTrackKey == trackKey {
                if prefetchInProgress, prefetchedVideoId == nil {
                    videoLoading = true
                    while prefetchInProgress, prefetchedVideoId == nil {
                        try? await Task.sleep(nanoseconds: 100_000_000)
                    }
                }
                videoId = prefetchedVideoId
                videoPlayUrl = prefetchedPlayUrl
                videoStreamError = prefetchedStreamError
                videoLoading = false
                if prefetchedVideoId != nil {
                    activeVideoTrackKey = trackKey
                }
                return
            }

            videoLoading = true
            guard let base = try? await repository.resolveBaseURL() else {
                videoStreamError = "Could not reach the server"
                videoLoading = false
                return
            }
            let lowBandwidth = !OfflineDownloadNetwork.shared.isOnWifi
            let durSec = Int(progress.durationMs / 1000)
            let prepared = await repository.prepareMusicVideoForTrack(
                title: title,
                artist: dev.artist,
                durationSec: durSec > 0 ? durSec : nil,
                baseUrl: base,
                lowBandwidth: lowBandwidth
            )
            videoId = prepared.videoId
            videoPlayUrl = prepared.playUrl
            videoStreamError = prepared.error
                ?? (prepared.playUrl == nil && prepared.videoId != nil ? "Could not prepare video stream" : nil)
            videoLoading = false
            if prepared.videoId != nil {
                activeVideoTrackKey = trackKey
            }
        }
        .onDisappear {
            preloadedPlayer?.pause()
            preloadedPlayer = nil
        }
        .task(id: "\(showVideo)|\(isLocalPhone)|\(dev.deviceId)|\(dev.paused)|\(dev.offset_ms)") {
            guard showVideo else { return }
            var anchorPos = isLocalPhone ? localPlayback.state.positionMs : dev.offset_ms
            var anchorAt = Date()
            while !Task.isCancelled {
                if isLocalPhone {
                    let s = localPlayback.state
                    if s.positionMs != anchorPos {
                        anchorPos = s.positionMs
                        anchorAt = Date()
                    }
                    let cap = s.durationMs > 0 ? s.durationMs : nil
                    if s.isPlaying && trackPlaying {
                        let live = anchorPos + Int64(Date().timeIntervalSince(anchorAt) * 1000)
                        videoPositionMs = cap.map { min(live, $0) } ?? live
                    } else {
                        videoPositionMs = s.positionMs
                    }
                } else if dev.paused {
                    videoPositionMs = dev.offset_ms
                } else {
                    videoPositionMs = computeNowPlayingProgress(
                        timestamp: dev.timestamp,
                        durationMs: dev.duration_ms,
                        offsetMs: dev.offset_ms,
                        paused: false
                    ).elapsedMs
                }
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
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
                releaseYear = nil
                return
            }
            releaseYear = await repository.trackYear(path: path)
            if let str = await repository.artworkURL(for: path), let url = URL(string: str) {
                guard dev.filepath == path else { return }
                artURL = url
            } else {
                artURL = initialArtURL
            }
        }
    }

    /// Top bar, bottom controls, and overlay toggles — one layout used for video and album-art modes.
    @ViewBuilder
    private func nowPlayingFixedChrome(
        progress: NowPlayingProgress,
        canControl: Bool,
        isLocalPhone: Bool,
        shuffleActive: Bool,
        loopActive: Bool,
        playContext: String?,
        safeTop: CGFloat,
        safeBottom: CGFloat
    ) -> some View {
        ZStack {
            VStack(spacing: 0) {
                NowPlayingSpotifyTopBar(
                    deviceName: dev.deviceName ?? dev.deviceId,
                    isLocal: isLocalPhone,
                    playContext: playContext,
                    onDismiss: { dismiss() },
                    onHistory: onHistory
                )
                .nowPlayingReadableShadow(true)
                .padding(.top, safeTop)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)

            VStack(spacing: 0) {
                Spacer(minLength: 0)
                nowPlayingBottomControls(
                    progress: progress,
                    canControl: canControl,
                    isLocalPhone: isLocalPhone,
                    shuffleActive: shuffleActive,
                    loopActive: loopActive
                )
                .padding(.top, 48)
                .padding(.bottom, safeBottom + (showPagerInset ? 28 : 16))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)

            HStack(spacing: 8) {
                if hasTrack {
                    LyricsModeToggleButton(showingLyrics: showLyrics) {
                        if showLyrics {
                            showLyrics = false
                        } else {
                            showLyrics = true
                            appState.preferences.nowPlayingVideo = false
                            ClientPrefsSync.schedulePush()
                            if lyrics == nil || ((lyrics?.lines.isEmpty ?? true) && (lyrics?.plain.isEmpty ?? true)) {
                                lyricsError = "No lyrics found for this track"
                            }
                        }
                    }
                }
                if hasTitle {
                    VideoModeToggleButton(showingVideo: showVideo, loading: showVideo && (videoLoading || prefetchInProgress)) {
                        let next = !showVideo
                        if next { showLyrics = false }
                        appState.preferences.nowPlayingVideo = next
                        ClientPrefsSync.schedulePush()
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .padding(.trailing, 16)
            .padding(.bottom, safeBottom + 16)
        }
        .id("nowPlayingFixedChrome")
    }

    /// Large cover / lyrics — decorative overlay only; does not affect chrome layout.
    @ViewBuilder
    private func centerAlbumHero(progress: NowPlayingProgress, safeTop: CGFloat) -> some View {
        GeometryReader { geo in
            let compact = geo.size.height < 500
            let maxArt = min(geo.size.width - 28, geo.size.height * (compact ? 0.46 : 0.50), geo.size.width * 0.88)
            let artSize = max(maxArt, 160)
            let topInset = safeTop + 52 + (compact ? 4 : 8)

            VStack(spacing: 0) {
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
                    } else {
                        BockArtwork(
                            url: artURL,
                            size: artSize,
                            cornerRadius: compact ? 6 : 8
                        )
                        .shadow(color: .black.opacity(0.35), radius: 20, y: 10)
                    }
                }
                .frame(width: artSize, height: artSize)
                Spacer(minLength: 0)
            }
            .padding(.top, topInset)
            .frame(width: geo.size.width, height: geo.size.height, alignment: .top)
        }
        .allowsHitTesting(showLyrics)
    }

    @ViewBuilder
    private func nowPlayingBottomControls(
        progress: NowPlayingProgress,
        canControl: Bool,
        isLocalPhone: Bool,
        shuffleActive: Bool,
        loopActive: Bool
    ) -> some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                let albumName: String? = {
                    guard let raw = dev.album?.trimmingCharacters(in: .whitespacesAndNewlines),
                          !raw.isEmpty else { return nil }
                    return raw
                }()
                if let albumName {
                    NavigationLink(value: SearchRoute.album(name: albumName, artist: dev.artist)) {
                        Text(dev.track ?? "Not playing")
                            .font(.title3.bold())
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                    NavigationLink(value: SearchRoute.album(name: albumName, artist: dev.artist)) {
                        Text(albumName)
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.88))
                            .multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                } else {
                    Text(dev.track ?? "Not playing")
                        .font(.title3.bold())
                        .foregroundStyle(.white)
                }
                if let artist = dev.artist?.trimmingCharacters(in: .whitespacesAndNewlines), !artist.isEmpty {
                    HStack(spacing: 0) {
                        NavigationLink(value: artist) {
                            Text(artist)
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.78))
                                .lineLimit(1)
                        }
                        .buttonStyle(.plain)
                        if let year = releaseYear {
                            Text(" · \(year)")
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.78))
                                .lineLimit(1)
                        }
                    }
                } else if let year = releaseYear {
                    Text(String(year))
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.78))
                        .lineLimit(1)
                }
                if let context = dev.sourceLabel ?? dev.playlist, !context.isEmpty {
                    Text(context)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .nowPlayingLetterGlow(true)

            if dev.sleep != nil {
                Button(action: onSleep) {
                    let sleep = dev.sleep!
                    let label = sleep.type == "time"
                        ? "Sleep \(sleep.remainingMin ?? 0)m"
                        : "\(sleep.remaining ?? 0) songs left"
                    Text(label)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BockColors.green)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.white.opacity(0.14))
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
            }

            Group {
                if isLocalPhone {
                    LocalSeekBar()
                } else if dev.duration_ms > 0 || progress.durationMs > 0 {
                    let dur = max(dev.duration_ms, progress.durationMs)
                    VStack(spacing: 4) {
                        Slider(value: .constant(progress.fraction))
                            .tint(.white)
                            .disabled(true)
                        HStack {
                            Text(formatPlaybackTime(seconds: progress.elapsedMs / 1000))
                            Spacer()
                            Text(formatPlaybackTime(seconds: dur / 1000))
                        }
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.65))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .nowPlayingReadableShadow(true)

            if canControl {
                nowPlayingTransportRow(
                    isLocalPhone: isLocalPhone,
                    shuffleActive: shuffleActive,
                    loopActive: loopActive,
                    sleepActive: dev.sleep != nil,
                    onSleep: onSleep,
                    showVolume: !isLocalPhone,
                    volume: viewModel.volume(for: dev.deviceId),
                    onVolumePreview: { viewModel.volumes[dev.deviceId] = $0 },
                    onVolumeCommit: { vol in
                        Task {
                            await viewModel.setVolume(repository: repository, device: dev, vol)
                        }
                    }
                )
                .nowPlayingReadableShadow(true)
            } else if !isLocalPhone {
                Text(unavailableControlsReason)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
            }

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
                            .foregroundStyle(.white.opacity(0.75))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.bottom, 8)
                .onTapGesture { onRoomQueue?() }
            }

            if remoteOk, dev.filepath != nil, !isLocalPhone {
                Button("Add to another room") { onAddToRoom?() }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.75))
            }

            if !upNextTracks.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Up next")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.85))
                    ForEach(Array(upNextTracks.prefix(3).enumerated()), id: \.offset) { idx, track in
                        Text("\(idx + 2). \(track.title ?? "—")\(track.artist.map { " — \($0)" } ?? "")")
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.55))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 20)
                .padding(.bottom, 12)
                .nowPlayingReadableShadow(true)
                .onTapGesture(perform: onUpNext)
            } else if canControl {
                Spacer().frame(height: 4)
            } else {
                Spacer().frame(height: 16)
            }
        }
    }

    private var unavailableControlsReason: String {
        if !viewModel.controlsAvailable {
            return "Playback controls aren't enabled on the server."
        }
        if !remoteOk {
            return "Alexa session expired — re-login in Settings to control this device."
        }
        return "Controls are unavailable for this device."
    }

    @ViewBuilder
    private func nowPlayingTransportRow(
        isLocalPhone: Bool,
        shuffleActive: Bool,
        loopActive: Bool,
        sleepActive: Bool,
        onSleep: @escaping () -> Void,
        showVolume: Bool = false,
        volume: Int = 50,
        onVolumePreview: @escaping (Int) -> Void = { _ in },
        onVolumeCommit: @escaping (Int) -> Void = { _ in }
    ) -> some View {
        let playSize: CGFloat = 72
        HStack(spacing: 0) {
            Button {
                Task {
                    let action = shuffleActive ? "shuffle_off" : "shuffle_on"
                    await viewModel.control(repository: repository, action: action, device: dev)
                }
            } label: {
                BockIcon(icon: .shuffle, size: 24)
                    .foregroundStyle(shuffleActive ? BockColors.green : .white.opacity(0.85))
                    .frame(width: 48, height: 48)
            }
            .buttonStyle(.plain)

            if !isLocalPhone {
                Button {
                    Task { await viewModel.control(repository: repository, action: "loop", device: dev) }
                } label: {
                    Image(systemName: "repeat")
                        .font(.system(size: 24))
                        .foregroundStyle(loopActive ? BockColors.green : .white.opacity(0.85))
                        .frame(width: 48, height: 48)
                }
                .buttonStyle(.plain)
            }

            Button {
                Task { await viewModel.control(repository: repository, action: "previous", device: dev) }
            } label: {
                BockIcon(icon: .skipPrevious, size: 32)
                    .frame(width: 52, height: 52)
            }
            .buttonStyle(.plain)

            Button {
                Task {
                    await viewModel.control(
                        repository: repository,
                        action: dev.paused ? "play" : "pause",
                        device: dev
                    )
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.white)
                        .frame(width: playSize, height: playSize)
                        .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
                    BockIcon(icon: dev.paused ? .playArrow : .pause, size: 32)
                        .foregroundStyle(.black)
                }
            }
            .buttonStyle(.plain)

            Button {
                Task { await viewModel.control(repository: repository, action: "next", device: dev) }
            } label: {
                BockIcon(icon: .skipNext, size: 32)
                    .frame(width: 52, height: 52)
            }
            .buttonStyle(.plain)

            if !isLocalPhone {
                Button(action: onSleep) {
                    BockIcon(icon: .bedtime, size: 24)
                        .foregroundStyle(sleepActive ? BockColors.green : .white.opacity(0.85))
                        .frame(width: 48, height: 48)
                }
                .buttonStyle(.plain)

                if showVolume {
                    AlexaVolumeHoldButton(
                        volume: volume,
                        onPreview: onVolumePreview,
                        onCommit: onVolumeCommit
                    )
                }
            } else {
                Color.clear.frame(width: 48, height: 48)
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 12)
        .padding(.top, 4)
    }
}

private enum AlexaVolumeSliderMetrics {
    static let trackHeight: CGFloat = 320
    static let trackWidth: CGFloat = 14
    static let borderInset: CGFloat = 2
    static var popupLift: CGFloat { trackHeight + 8 }
}

private struct VerticalVolumeSliderTrack: View {
    let value: Double

    var body: some View {
        let h = AlexaVolumeSliderMetrics.trackHeight
        let inset = AlexaVolumeSliderMetrics.borderInset
        let innerH = h - inset * 2
        let fraction = min(1, max(0, value / 100))
        let fillH = innerH * fraction
        ZStack(alignment: .bottom) {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(red: 0x28 / 255, green: 0x28 / 255, blue: 0x28 / 255).opacity(0.92))
                .frame(width: AlexaVolumeSliderMetrics.trackWidth, height: h)
            RoundedRectangle(cornerRadius: 6)
                .fill(BockColors.green)
                .frame(
                    width: AlexaVolumeSliderMetrics.trackWidth - inset * 2,
                    height: max(fillH, 0)
                )
                .padding(.bottom, inset)
        }
        .frame(width: AlexaVolumeSliderMetrics.trackWidth, height: h)
    }
}

private struct AlexaVolumeHoldButton: View {
    let volume: Int
    var enabled: Bool = true
    let onPreview: (Int) -> Void
    let onCommit: (Int) -> Void

    @State private var showSlider = false
    @State private var previewVolume: Double = 50
    @State private var dragStartVolume: Double = 50

    var body: some View {
        BockIcon(icon: .volumeUp, size: 24)
            .foregroundStyle(showSlider ? BockColors.green : .white.opacity(enabled ? 0.85 : 0.3))
            .frame(width: 48, height: 48)
            .overlay(alignment: .bottom) {
                if showSlider {
                    VerticalVolumeSliderTrack(value: previewVolume)
                        .offset(y: -AlexaVolumeSliderMetrics.popupLift)
                }
            }
            .frame(width: 48, height: 48)
            .zIndex(showSlider ? 10 : 0)
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    guard enabled else { return }
                    if !showSlider {
                        showSlider = true
                        dragStartVolume = Double(volume)
                        previewVolume = dragStartVolume
                    }
                    let delta = -value.translation.height / 1.5
                    previewVolume = min(100, max(0, dragStartVolume + delta))
                    onPreview(Int(previewVolume.rounded()))
                }
                .onEnded { _ in
                    guard enabled else { return }
                    showSlider = false
                    onCommit(Int(previewVolume.rounded()))
                }
        )
        .onChange(of: volume) { _, newValue in
            if !showSlider {
                previewVolume = Double(newValue)
            }
        }
    }
}

private struct NowPlayingLetterGlow: ViewModifier {
    func body(content: Content) -> some View {
        content
            .shadow(color: Color(white: 0.91).opacity(0.85), radius: 1)
            .shadow(color: Color(white: 0.82).opacity(0.65), radius: 4)
            .shadow(color: Color(white: 0.72).opacity(0.4), radius: 8)
    }
}

private struct NowPlayingVideoTextShadow: ViewModifier {
    func body(content: Content) -> some View {
        content
            .shadow(color: .black.opacity(0.85), radius: 6, y: 2)
            .shadow(color: .black.opacity(0.45), radius: 12, y: 4)
    }
}

private extension View {
    @ViewBuilder
    func nowPlayingLetterGlow(_ enabled: Bool) -> some View {
        if enabled {
            modifier(NowPlayingLetterGlow())
        } else {
            self
        }
    }

    @ViewBuilder
    func nowPlayingReadableShadow(_ enabled: Bool) -> some View {
        if enabled {
            modifier(NowPlayingVideoTextShadow())
        } else {
            self
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
            .tint(.white)
            .disabled(durationMs <= 0)

            HStack {
                Text(formatPlaybackTime(seconds: shownMs / 1000))
                Spacer()
                Text(formatPlaybackTime(seconds: durationMs / 1000))
            }
            .font(.caption)
            .foregroundStyle(.white.opacity(0.65))
        }
    }
}

private struct NowPlayingArtBackdrop: View {
    let url: URL?
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Group {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: proxy.size.width, height: proxy.size.height)
                            .scaleEffect(1.25)
                            .blur(radius: 56)
                    } else {
                        Color(white: 0.1)
                    }
                }
                .clipped()
            }
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

private func musicVideoTrackKey(title: String, artist: String?) -> String {
    let a = artist?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
    return "\(a)|\(title.lowercased())"
}
