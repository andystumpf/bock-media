import Foundation

/// Single source-of-truth poller for now-playing device state.
///
/// Previously the Now Playing screen (1.5s) and the mini bar (3–5s) each ran
/// their own polling loop hitting `/api/nowplaying_devices` independently. This
/// service unifies them into one loop that:
///   - polls faster while something is playing and slower while idle,
///   - pauses entirely while the app is backgrounded,
///   - only runs while at least one subscriber (mini bar / Now Playing) is active.
///
/// Cadence is the optimized profile agreed for both platforms (see
/// `docs/IOS_PARITY_PLAN.md` open decision #1): 5s playing / 20s idle.
@MainActor
final class NowPlayingPollService: ObservableObject {
    static let shared = NowPlayingPollService()

    @Published var items: [NowPlayingDeviceItem] = []
    @Published var controlsAvailable = false
    @Published var alexaDevices: [AlexaDevice] = []
    @Published var lastUpdated: Date?

    private static let playingIntervalNs: UInt64 = 5_000_000_000
    private static let idleIntervalNs: UInt64 = 20_000_000_000

    private var repository: BockMediaRepository?
    private var subscribers = 0
    private var isForeground = true
    private var loop: Task<Void, Never>?

    private init() {}

    func configure(repository: BockMediaRepository) {
        self.repository = repository
    }

    func addSubscriber() {
        subscribers += 1
        startIfNeeded()
    }

    func removeSubscriber() {
        subscribers = max(0, subscribers - 1)
        if subscribers == 0 { stopLoop() }
    }

    func setForeground(_ foreground: Bool) {
        guard foreground != isForeground else { return }
        isForeground = foreground
        if foreground {
            startIfNeeded()
            Task { await refreshNow() }
        } else {
            stopLoop()
        }
    }

    /// Forces an immediate refresh (e.g. after a control action or focus change).
    func refreshNow() async {
        await tick()
    }

    private func startIfNeeded() {
        guard loop == nil, subscribers > 0, isForeground, repository != nil else { return }
        loop = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.tick()
                let interval = self.isPlaying ? Self.playingIntervalNs : Self.idleIntervalNs
                try? await Task.sleep(nanoseconds: interval)
            }
        }
    }

    private func stopLoop() {
        loop?.cancel()
        loop = nil
    }

    private var isPlaying: Bool {
        if LocalPlaybackController.shared.state.isPlaying { return true }
        return items.contains { !$0.paused && ($0.track != nil || $0.artist != nil) }
    }

    private func tick() async {
        guard let repository else { return }
        let result = await WidgetSessionBridge.fetchNowPlayingItems(
            repository: repository,
            alexaDevices: alexaDevices
        )
        alexaDevices = result.alexaDevices
        items = result.items
        controlsAvailable = result.controlsAvailable
        lastUpdated = Date()
        await WidgetSessionBridge.update(
            repository: repository,
            items: result.items,
            controlsAvailable: result.controlsAvailable
        )
    }
}
