import Foundation

enum HomeLoadCoordinator {
    private static let minReloadMs: Int64 = 600_000
    private static var lastSuccessfulLoadMs: Int64 = 0
    private static let loadGate = LoadGate()

    static func markLoaded() {
        lastSuccessfulLoadMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func resetReloadWindow() {
        lastSuccessfulLoadMs = 0
    }

    static func shouldSkipReload() -> Bool {
        guard let cached = HomeFeedCache.peek(), cached.hasCurrentHomeLayout() else { return false }
        let missingRated = !cached.sections.contains(where: { $0.kind == .ratedSongs })
        if missingRated, HomeFeedCache.peekHasRatedSongs() == true { return false }
        return Int64(Date().timeIntervalSince1970 * 1000) - lastSuccessfulLoadMs < minReloadMs
    }

    /// True when a network reload should replace the on-screen feed.
    static func shouldPaintFreshHomeFeed(hadVisibleFeed: Bool, withinSkipReloadWindow: Bool) -> Bool {
        !hadVisibleFeed || !withinSkipReloadWindow
    }

    /// True only after a genuine profile switch — not on first mount.
    static func shouldReloadHomeForProfileSwitch(previousMemberId: String?, currentMemberId: String?) -> Bool {
        guard let previousMemberId else { return false }
        return previousMemberId != currentMemberId
    }

    static let resumePullDebounceMs: Int64 = 5_000

    static func shouldSkipResumePull(lastPullCompletedMs: Int64, nowMs: Int64, debounceMs: Int64 = resumePullDebounceMs) -> Bool {
        lastPullCompletedMs > 0 && nowMs - lastPullCompletedMs < debounceMs
    }

    static func withLoadLock<T>(_ block: () async -> T) async -> T {
        await loadGate.run(block)
    }
}

private actor LoadGate {
    func run<T>(_ block: () async -> T) async -> T {
        await block()
    }
}
