import Foundation

enum HomeLoadCoordinator {
    private static let minReloadMs: Int64 = 45_000
    private static var lastSuccessfulLoadMs: Int64 = 0

    static func markLoaded() {
        lastSuccessfulLoadMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func shouldSkipReload() -> Bool {
        guard let cached = HomeFeedCache.getIfFresh(), cached.hasCurrentHomeLayout() else { return false }
        return Int64(Date().timeIntervalSince1970 * 1000) - lastSuccessfulLoadMs < minReloadMs
    }

    static func withLoadLock<T>(_ block: () async -> T) async -> T {
        await block()
    }
}
