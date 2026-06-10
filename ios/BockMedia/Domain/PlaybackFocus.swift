import Foundation

/// Remembers which speaker the user last started playback on.
enum PlaybackFocus {
    private static let groupPrefix = "group:"

    private(set) static var pendingDeviceValue: String?
    private(set) static var pendingDeviceLabel: String?
    private(set) static var focusedDeviceId: String?
    private(set) static var generation = 0

    static func notePlayStarted(_ deviceValue: String, label: String?) {
        pendingDeviceValue = deviceValue.trimmingCharacters(in: .whitespaces).nilIfBlank
        pendingDeviceLabel = label?.trimmingCharacters(in: .whitespaces).nilIfBlank
        generation += 1
    }

    @discardableResult
    static func syncPendingFocus(items: [NowPlayingDeviceItem], alexaDevices: [AlexaDevice] = []) -> Bool {
        guard let deviceValue = pendingDeviceValue else { return false }
        guard let match = findMatchingItem(items: items, deviceValue: deviceValue, label: pendingDeviceLabel, alexaDevices: alexaDevices) else {
            return false
        }
        focusedDeviceId = match.deviceId
        pendingDeviceValue = nil
        pendingDeviceLabel = nil
        generation += 1
        return true
    }

    static func focusDeviceId(_ deviceId: String) {
        focusedDeviceId = deviceId
        pendingDeviceValue = nil
        pendingDeviceLabel = nil
        generation += 1
    }

    static func resolveFocusedItem(items: [NowPlayingDeviceItem], alexaDevices: [AlexaDevice] = []) -> NowPlayingDeviceItem? {
        guard !items.isEmpty else { return nil }
        _ = syncPendingFocus(items: items, alexaDevices: alexaDevices)
        if let id = focusedDeviceId, let item = items.first(where: { $0.deviceId == id }) {
            return item
        }
        return items.first(where: { !$0.paused }) ?? items.first
    }

    private static func findMatchingItem(
        items: [NowPlayingDeviceItem],
        deviceValue: String,
        label: String?,
        alexaDevices: [AlexaDevice]
    ) -> NowPlayingDeviceItem? {
        if let label, let match = matchByName(items: items, name: label) { return match }

        if !deviceValue.hasPrefix(groupPrefix) {
            if let alexaName = alexaDevices.first(where: { $0.serial == deviceValue })?.name,
               let match = matchByName(items: items, name: alexaName) {
                return match
            }
        }
        return nil
    }

    private static func matchByName(items: [NowPlayingDeviceItem], name: String) -> NowPlayingDeviceItem? {
        if let exact = items.first(where: { ($0.deviceName ?? "").caseInsensitiveCompare(name) == .orderedSame }) {
            return exact
        }
        return items.first { item in
            guard let deviceName = item.deviceName else { return false }
            return deviceName.localizedCaseInsensitiveContains(name) || name.localizedCaseInsensitiveContains(deviceName)
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
