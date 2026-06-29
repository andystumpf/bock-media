import Foundation

/// Stable install identity — stub for Phase 6 per-device accounts.
enum ClientIdStore {
    private static let key = "client_id"

    static func clientId() -> String {
        if let existing = KeychainStore.read(key), !existing.isEmpty {
            return existing
        }
        let id = UUID().uuidString.lowercased()
        KeychainStore.write(key, value: id)
        return id
    }
}
