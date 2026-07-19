import Foundation

/// In-memory automations list — invalidate on profile switch (parity with Android).
enum AutomationSessionCache {
    private static let ttlMs: Int64 = 600_000
    private static var snapshot: ([AutomationItem], remoteOk: Bool)?
    private static var cachedAtMs: Int64 = 0

    static func peek() -> ([AutomationItem], remoteOk: Bool)? { snapshot }

    static func getIfFresh() -> ([AutomationItem], remoteOk: Bool)? {
        guard let snapshot else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard now - cachedAtMs < ttlMs else { return nil }
        return snapshot
    }

    static func put(items: [AutomationItem], remoteOk: Bool) {
        snapshot = (items, remoteOk)
        cachedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func invalidate() {
        snapshot = nil
        cachedAtMs = 0
    }
}
