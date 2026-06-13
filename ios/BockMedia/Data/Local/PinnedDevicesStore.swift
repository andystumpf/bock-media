import Foundation

enum PinnedDevicesStore {
    private static let key = "pinned_devices_csv"
    private static let maxPinned = 6

    static func pinned() -> [String] {
        UserDefaults.standard.string(forKey: key)?
            .split(separator: "|")
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty } ?? []
    }

    static func setPinned(_ values: [String]) {
        let trimmed = Array(values.prefix(maxPinned))
        if trimmed.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(trimmed.joined(separator: "|"), forKey: key)
        }
    }

    static func toggle(_ serial: String) {
        var current = pinned()
        if let idx = current.firstIndex(of: serial) {
            current.remove(at: idx)
        } else {
            current.insert(serial, at: 0)
        }
        setPinned(current)
    }

    static func isPinned(_ serial: String) -> Bool {
        pinned().contains(serial)
    }
}
