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
    private var timeObserver: Any?
    private var lastPlaybackReportMs: Int64 = 0

    private init() {
        configureAudioSession()
    }

    func playTarget(repository: BockMediaRepository, target: PlayTarget, shuffle: Bool) async {
        analyticsRepository = repository
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

    func togglePlayPause() {
        guard let player else { return }
        if state.isPlaying {
            player.pause()
            state.isPlaying = false
        } else {
            player.play()
            state.isPlaying = true
        }
        reportPlaybackState(force: true)
        notifyWidgetSession()
    }

    func skipNext() {
        guard !state.tracks.isEmpty else { return }
        state.index = (state.index + 1) % state.tracks.count
        Task { try? await playCurrent() }
    }

    func playAtIndex(_ index: Int) {
        guard state.tracks.indices.contains(index) else { return }
        state.index = index
        Task { try? await playCurrent() }
    }

    func skipPrevious() {
        guard !state.tracks.isEmpty else { return }
        state.index = state.index > 0 ? state.index - 1 : state.tracks.count - 1
        Task { try? await playCurrent() }
    }

    func stopPlayback() {
        player?.pause()
        removeTimeObserver()
        player = nil
        if let analyticsRepository {
            DeviceAnalyticsReporter.clearPlayback(repository: analyticsRepository)
        }
        state = LocalPlaybackState()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        notifyWidgetSession()
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
        removeTimeObserver()
        configureAudioSession()

        let item = AVPlayerItem(url: track.playbackURL)
        if player == nil {
            player = AVPlayer(playerItem: item)
        } else {
            player?.replaceCurrentItem(with: item)
        }
        player?.play()
        state.isPlaying = true
        updateNowPlayingInfo(track: track)
        observeTime()
        if let analyticsRepository {
            DeviceAnalyticsReporter.reportPlay(repository: analyticsRepository, track: track)
        }
        notifyWidgetSession()
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
        ]
        if let artist = track.artist { info[MPMediaItemPropertyArtist] = artist }
        if let album = track.album { info[MPMediaItemPropertyAlbumTitle] = album }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    private func observeTime() {
        guard let player else { return }
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self, let player = self.player else { return }
                let ms = Int64(time.seconds * 1000)
                self.state.positionMs = ms
                if let duration = player.currentItem?.duration.seconds, duration.isFinite {
                    self.state.durationMs = Int64(duration * 1000)
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
