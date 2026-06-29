import AVFoundation
import Foundation
import MediaPlayer

struct LocalTrack: Identifiable, Equatable {
    let path: String
    let title: String
    let artist: String?
    let album: String?
    let streamURL: URL
    var localFileURL: URL?
    var id: String { path }

    var playbackURL: URL { localFileURL ?? streamURL }
}

struct LocalPlaybackState: Equatable {
    var active = false
    var tracks: [LocalTrack] = []
    var index = 0
    var isPlaying = false
    var positionMs: Int64 = 0
    var durationMs: Int64 = 0
    var shuffle = false
    var loading = false
    var error: String?

    var current: LocalTrack? { tracks.indices.contains(index) ? tracks[index] : nil }
}

enum LocalPlaybackIds {
    static let localPhoneDeviceId = "local-phone"
}

@MainActor
final class LocalPlaybackController: ObservableObject {
    static let shared = LocalPlaybackController()

    @Published private(set) var state = LocalPlaybackState()
    private var analyticsRepository: BockMediaRepository?

    private var player: AVPlayer?
    private var incomingPlayer: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var lastPlaybackReportMs: Int64 = 0
    private var remoteCommandsConfigured = false
    private var currentArtwork: MPMediaItemArtwork?
    private var artworkToken = 0
    private var crossfading = false
    private var crossfadeTimer: Timer?
    private var crossfadeStartedAt: Date?
    private var crossfadeDuration: TimeInterval = 0
    private var activePlayTarget: PlayTarget?

    private var crossfadeSeconds: Int {
        min(20, max(0, UserDefaults.standard.integer(forKey: "crossfade_seconds")))
    }

    private init() {
        configureAudioSession()
        setupRemoteCommands()
    }

    func playTarget(repository: BockMediaRepository, target: PlayTarget, shuffle: Bool) async {
        analyticsRepository = repository
        activePlayTarget = target
        state.loading = true
        state.error = nil
        do {
            let offlineStore = OfflineDownloadStore()
            if let manifest = offlineStore.readManifest(target.downloadId()),
               offlineStore.isCollectionComplete(manifest) {
                let tracks = offlineStore.localTracks(for: manifest)
                guard !tracks.isEmpty else { throw LocalPlaybackError.noTracks }
                try await playTracks(tracks, shuffle: shuffle)
                return
            }
            let tracks = try await LocalPlaybackQueueResolver.resolve(repository: repository, target: target)
            guard !tracks.isEmpty else { throw LocalPlaybackError.noTracks }
            try await playTracks(tracks, shuffle: shuffle)
        } catch {
            state.loading = false
            state.error = error.localizedDescription
            notifyWidgetSession()
        }
    }

    func playOfflineManifest(_ manifest: OfflineCollectionManifest, shuffle: Bool = false, startIndex: Int = 0) async {
        state.loading = true
        state.error = nil
        let tracks = OfflineDownloadStore().localTracks(for: manifest)
        do {
            guard !tracks.isEmpty else { throw LocalPlaybackError.noTracks }
            try await playTracks(tracks, shuffle: shuffle, startIndex: startIndex)
        } catch {
            state.loading = false
            state.error = error.localizedDescription
            notifyWidgetSession()
        }
    }

    func playTracks(_ tracks: [LocalTrack], shuffle: Bool, startIndex: Int = 0) async throws {
        cancelCrossfade()
        let ordered = shuffle ? tracks.shuffled() : tracks
        let index = min(max(0, startIndex), max(0, ordered.count - 1))
        state = LocalPlaybackState(
            active: true,
            tracks: ordered,
            index: index,
            isPlaying: true,
            shuffle: shuffle,
            loading: false
        )
        try await playCurrent()
        notifyWidgetSession()
    }

    func setPlayContext(repository: BockMediaRepository, target: PlayTarget) {
        analyticsRepository = repository
        activePlayTarget = target
    }

    func setShuffle(_ on: Bool) {
        guard state.active, !state.tracks.isEmpty else { return }
        guard state.shuffle != on else { return }
        let current = state.tracks[state.index]
        if on {
            var rest = state.tracks
            rest.remove(at: state.index)
            rest.shuffle()
            state.tracks = [current] + rest
            state.index = 0
        }
        state.shuffle = on
        Task { try? await playCurrent() }
        notifyWidgetSession()
    }

    func toggleShuffle() {
        setShuffle(!state.shuffle)
    }

    func togglePlayPause() {
        if crossfading {
            let playing = incomingPlayer?.rate ?? 0 > 0
            if playing {
                incomingPlayer?.pause()
                player?.pause()
                state.isPlaying = false
            } else {
                incomingPlayer?.play()
                player?.play()
                state.isPlaying = true
            }
        } else {
            guard let player else { return }
            if state.isPlaying {
                player.pause()
                state.isPlaying = false
            } else {
                player.play()
                state.isPlaying = true
            }
        }
        if let track = state.current { updateNowPlayingInfo(track: track) }
        reportPlaybackState(force: true)
        notifyWidgetSession()
    }

    func skipNext() {
        guard !state.tracks.isEmpty else { return }
        cancelCrossfade()
        if state.index >= state.tracks.count - 1 {
            Task {
                if await tryContinuePlayback() { return }
                stopPlayback()
            }
            return
        }
        state.index += 1
        Task { try? await playCurrent() }
    }

    func playAtIndex(_ index: Int) {
        guard state.tracks.indices.contains(index) else { return }
        cancelCrossfade()
        state.index = index
        Task { try? await playCurrent() }
    }

    func skipPrevious() {
        guard !state.tracks.isEmpty else { return }
        cancelCrossfade()
        state.index = state.index > 0 ? state.index - 1 : state.tracks.count - 1
        Task { try? await playCurrent() }
    }

    func seek(toSeconds seconds: Double) {
        guard seconds.isFinite, seconds >= 0 else { return }
        let target = CMTime(seconds: seconds, preferredTimescale: 600)
        let active = crossfading ? incomingPlayer : player
        guard let active else { return }
        active.seek(to: target) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.state.positionMs = Int64(seconds * 1000)
                if let track = self.state.current { self.updateNowPlayingInfo(track: track) }
            }
        }
    }

    func stopPlayback() {
        cancelCrossfade()
        player?.pause()
        removeTimeObserver()
        removeEndObserver()
        player = nil
        activePlayTarget = nil
        if let analyticsRepository {
            DeviceAnalyticsReporter.clearPlayback(repository: analyticsRepository)
        }
        state = LocalPlaybackState()
        currentArtwork = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        setRemoteCommandsEnabled(false)
        notifyWidgetSession()
    }

    private func handleTrackEnded() {
        guard state.active, !state.tracks.isEmpty else { return }
        if crossfading { return }
        // Stale AVPlayerItemDidPlayToEndTime from the outgoing track during crossfade.
        if let item = player?.currentItem,
           item.status == .readyToPlay,
           item.duration.isNumeric,
           item.currentTime().seconds < item.duration.seconds - 0.5 {
            return
        }
        if state.index >= state.tracks.count - 1 {
            Task {
                if await tryContinuePlayback() { return }
                stopPlayback()
            }
        } else {
            state.index += 1
            Task { try? await playCurrent() }
        }
    }

    private var continueAfterQueueMode: String {
        UserDefaults.standard.string(forKey: "continue_after_queue") ?? "off"
    }

    private func tryContinuePlayback() async -> Bool {
        let mode = continueAfterQueueMode
        if mode == "off" || mode.isEmpty { return false }
        guard let repository = analyticsRepository, let target = activePlayTarget else { return false }
        if case .radio = target { return false }
        guard let last = state.current else { return false }
        do {
            let resp: DiscoveryMixResponse
            if mode == "artist_radio" {
                let artist = last.artist?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !artist.isEmpty else { return false }
                resp = try await repository.resonanceRadio(
                    seedKind: "artist", artist: artist, maxTracks: 30
                )
            } else {
                switch target {
                case .playlist(let id, _):
                    resp = try await repository.resonanceRadio(
                        seedKind: "playlist", playlistId: id, maxTracks: 30
                    )
                case .album(let name, let artist):
                    resp = try await repository.resonanceRadio(
                        seedKind: "album", album: name, artist: artist, maxTracks: 30
                    )
                case .artist(let name):
                    resp = try await repository.resonanceRadio(
                        seedKind: "artist", artist: name, maxTracks: 30
                    )
                default:
                    resp = try await repository.resonanceRadio(
                        seedKind: "song", path: last.path, maxTracks: 30
                    )
                }
            }
            var appended: [LocalTrack] = []
            let seen = Set(state.tracks.map(\.path))
            for t in resp.tracks {
                guard let path = t.path, !path.isEmpty, !seen.contains(path),
                      let urlStr = await repository.streamURL(for: path),
                      let url = URL(string: urlStr) else { continue }
                appended.append(LocalTrack(
                    path: path, title: t.title ?? path, artist: t.artist,
                    album: t.album, streamURL: url, localFileURL: nil
                ))
            }
            guard !appended.isEmpty else { return false }
            state.tracks.append(contentsOf: appended)
            state.index += 1
            try await playCurrent()
            return true
        } catch {
            return false
        }
    }

    func nowPlayingDeviceItem() -> NowPlayingDeviceItem? {
        guard state.active, let track = state.current else { return nil }
        return NowPlayingDeviceItem(
            deviceId: LocalPlaybackIds.localPhoneDeviceId,
            deviceName: "This iPhone",
            track: track.title,
            artist: track.artist,
            album: track.album,
            filepath: track.path,
            timestamp: Date().timeIntervalSince1970,
            duration_ms: state.durationMs,
            offset_ms: state.positionMs,
            paused: !state.isPlaying
        )
    }

    private func playCurrent() async throws {
        guard let track = state.current else { return }
        cancelCrossfade()
        removeTimeObserver()
        removeEndObserver()
        configureAudioSession()
        currentArtwork = nil

        let item = AVPlayerItem(url: track.playbackURL)
        if player == nil {
            player = AVPlayer(playerItem: item)
        } else {
            player?.replaceCurrentItem(with: item)
        }
        player?.volume = 1
        observeEnd(for: item, player: player)
        player?.play()
        state.isPlaying = true
        setRemoteCommandsEnabled(true)
        updateNowPlayingInfo(track: track)
        observeTime()
        loadArtwork(for: track)
        if let analyticsRepository {
            DeviceAnalyticsReporter.reportPlay(repository: analyticsRepository, track: track)
        }
        notifyWidgetSession()
    }

    private func startCrossfade(overlapSeconds: TimeInterval) {
        guard !crossfading, crossfadeSeconds > 0 else { return }
        guard state.index < state.tracks.count - 1 else { return }
        let nextIndex = state.index + 1
        let nextTrack = state.tracks[nextIndex]
        crossfading = true
        crossfadeDuration = max(0.05, overlapSeconds)
        crossfadeStartedAt = Date()

        // Outgoing track must not fire handleTrackEnded once we've advanced the queue index.
        removeEndObserver()

        let item = AVPlayerItem(url: nextTrack.playbackURL)
        let incoming = AVPlayer(playerItem: item)
        incoming.volume = 0
        incoming.play()
        incomingPlayer = incoming
        state.index = nextIndex
        state.isPlaying = true
        updateNowPlayingInfo(track: nextTrack)
        loadArtwork(for: nextTrack)
        if let analyticsRepository {
            DeviceAnalyticsReporter.reportPlay(repository: analyticsRepository, track: nextTrack)
        }

        crossfadeTimer?.invalidate()
        crossfadeTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] timer in
            Task { @MainActor [weak self] in
                guard let self, self.crossfading,
                      let started = self.crossfadeStartedAt,
                      let outgoing = self.player,
                      let incoming = self.incomingPlayer else {
                    timer.invalidate()
                    return
                }
                let elapsed = Date().timeIntervalSince(started)
                let progress = min(1, elapsed / self.crossfadeDuration)
                outgoing.volume = Float(1 - progress)
                incoming.volume = Float(progress)
                if progress >= 1 {
                    timer.invalidate()
                    self.completeCrossfade()
                }
            }
        }
    }

    private func completeCrossfade() {
        crossfadeTimer?.invalidate()
        crossfadeTimer = nil
        crossfadeStartedAt = nil
        guard crossfading else { return }
        // Tear down the periodic time observer while `self.player` still references the
        // outgoing player that created it — AVPlayer.removeTimeObserver must be called on
        // the same player that added the token, otherwise it's invalid and can crash.
        removeTimeObserver()
        let outgoing = player
        outgoing?.pause()
        outgoing?.replaceCurrentItem(with: nil)
        if let incoming = incomingPlayer {
            player = incoming
            incomingPlayer = nil
            if let item = player?.currentItem {
                observeEnd(for: item, player: player)
            }
        }
        player?.volume = 1
        crossfading = false
        observeTime()
        if let track = state.current {
            updateNowPlayingInfo(track: track)
            reportPlaybackState(force: true)
        }
        notifyWidgetSession()
    }

    private func cancelCrossfade() {
        crossfadeTimer?.invalidate()
        crossfadeTimer = nil
        crossfadeStartedAt = nil
        crossfading = false
        player?.volume = 1
        incomingPlayer?.pause()
        incomingPlayer?.replaceCurrentItem(with: nil)
        incomingPlayer = nil
    }

    private func loadArtwork(for track: LocalTrack) {
        guard let analyticsRepository else { return }
        artworkToken += 1
        let token = artworkToken
        let path = track.path
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard let urlStr = await analyticsRepository.artworkURL(for: path),
                  let url = URL(string: urlStr),
                  let image = await ArtworkImageCache.load(url) else { return }
            guard token == self.artworkToken, self.state.current?.path == path else { return }
            self.currentArtwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.updateNowPlayingInfo(track: track)
        }
    }

    private func notifyWidgetSession() {
        NotificationCenter.default.post(name: .localPlaybackDidChange, object: nil)
    }

    private func configureAudioSession() {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    private func updateNowPlayingInfo(track: LocalTrack) {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyPlaybackDuration: Double(state.durationMs) / 1000,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(state.positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: state.isPlaying ? 1 : 0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPMediaItemPropertyPersistentID: UInt64(bitPattern: Int64(track.path.hashValue)),
        ]
        if !state.tracks.isEmpty {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = state.index
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = state.tracks.count
        }
        if let artist = track.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = track.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let currentArtwork { info[MPMediaItemPropertyArtwork] = currentArtwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func setupRemoteCommands() {
        guard !remoteCommandsConfigured else { return }
        remoteCommandsConfigured = true
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.state.active else { return .noSuchContent }
                if !self.state.isPlaying { self.togglePlayPause() }
                return .success
            }
        }
        center.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.state.active else { return .noSuchContent }
                if self.state.isPlaying { self.togglePlayPause() }
                return .success
            }
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.state.active else { return .noSuchContent }
                self.togglePlayPause()
                return .success
            }
        }
        center.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.state.active, self.state.tracks.count > 1 else { return .noSuchContent }
                self.skipNext()
                return .success
            }
        }
        center.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.state.active, self.state.tracks.count > 1 else { return .noSuchContent }
                self.skipPrevious()
                return .success
            }
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            MainActor.assumeIsolated {
                guard let self, self.state.active,
                      let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
                self.seek(toSeconds: e.positionTime)
                return .success
            }
        }
        setRemoteCommandsEnabled(false)
    }

    private func setRemoteCommandsEnabled(_ enabled: Bool) {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.isEnabled = enabled
        center.pauseCommand.isEnabled = enabled
        center.togglePlayPauseCommand.isEnabled = enabled
        center.changePlaybackPositionCommand.isEnabled = enabled
        let multi = enabled && state.tracks.count > 1
        center.nextTrackCommand.isEnabled = multi
        center.previousTrackCommand.isEnabled = multi
    }

    private func observeTime() {
        guard let player else { return }
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.1, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let active = self.crossfading ? self.incomingPlayer : self.player
                guard let active else { return }
                let ms = Int64(time.seconds * 1000)
                if !self.crossfading {
                    self.state.positionMs = ms
                } else if let incoming = self.incomingPlayer {
                    self.state.positionMs = Int64(incoming.currentTime().seconds * 1000)
                }
                if let duration = active.currentItem?.duration.seconds, duration.isFinite, duration > 0 {
                    self.state.durationMs = Int64(duration * 1000)
                    if !self.crossfading,
                       self.crossfadeSeconds > 0,
                       self.state.index < self.state.tracks.count - 1 {
                        let remaining = duration - time.seconds
                        if remaining <= Double(self.crossfadeSeconds) {
                            self.startCrossfade(overlapSeconds: min(Double(self.crossfadeSeconds), remaining))
                        }
                    }
                }
                if let track = self.state.current {
                    self.updateNowPlayingInfo(track: track)
                    self.reportPlaybackState()
                }
            }
        }
    }

    private func reportPlaybackState(force: Bool = false) {
        guard state.active, let track = state.current, let analyticsRepository else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if !force, now - lastPlaybackReportMs < 4_000 { return }
        lastPlaybackReportMs = now
        DeviceAnalyticsReporter.reportPlayback(
            repository: analyticsRepository,
            track: track,
            playing: state.isPlaying,
            offsetMs: state.positionMs,
            durationMs: state.durationMs,
            force: force
        )
    }

    private func removeTimeObserver() {
        if let timeObserver, let player {
            player.removeTimeObserver(timeObserver)
        }
        timeObserver = nil
    }

    private func observeEnd(for item: AVPlayerItem, player: AVPlayer?) {
        removeEndObserver()
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.handleTrackEnded() }
        }
    }

    private func removeEndObserver() {
        if let endObserver {
            NotificationCenter.default.removeObserver(endObserver)
        }
        endObserver = nil
    }
}

enum LocalPlaybackError: LocalizedError {
    case noTracks
    case missingStreamURL

    var errorDescription: String? {
        switch self {
        case .noTracks: return "No playable tracks found"
        case .missingStreamURL: return "Missing stream URL"
        }
    }
}
