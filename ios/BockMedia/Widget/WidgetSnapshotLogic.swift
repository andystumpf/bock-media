import Foundation

/// Pure layout/ordering rules shared by the app, widget extension, and tests.
enum WidgetSnapshotLogic {
    static let localDeviceId = "local-phone"
    static let maxMediumDevices = 2
    static let maxLargeDevices = 4

    static func deviceLabel(for item: WidgetNowPlayingItem) -> String {
        if item.isLocal { return "This iPhone" }
        let name = item.deviceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "Speaker" : name
    }

    static func displayable(_ items: [WidgetNowPlayingItem]) -> [WidgetNowPlayingItem] {
        items.filter { item in
            let hasTrack = !(item.track?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            return hasTrack || !item.paused || item.isLocal
        }
    }

    /// Filter empty rows, then apply stable multi-device ordering for the widget.
    static func itemsForWidgetDisplay(_ items: [WidgetNowPlayingItem]) -> [WidgetNowPlayingItem] {
        orderedForDisplay(displayable(items))
    }

    /// Local phone first, then actively playing speakers, then paused — stable alphabetical tie-break.
    static func orderedForDisplay(_ items: [WidgetNowPlayingItem]) -> [WidgetNowPlayingItem] {
        items.sorted { lhs, rhs in
            if lhs.isLocal != rhs.isLocal { return lhs.isLocal }
            if lhs.paused != rhs.paused { return !lhs.paused }
            return deviceLabel(for: lhs).localizedCaseInsensitiveCompare(deviceLabel(for: rhs)) == .orderedAscending
        }
    }

    static func playingCount(_ items: [WidgetNowPlayingItem]) -> Int {
        items.filter { !$0.paused && !( $0.track?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }.count
    }

    static func controlAction(for item: WidgetNowPlayingItem) -> String {
        item.paused ? "play" : "pause"
    }

    static func controlURL(deviceId: String, action: String) -> URL? {
        var components = URLComponents()
        components.scheme = "bockmedia"
        components.host = "control"
        components.queryItems = [
            URLQueryItem(name: "deviceId", value: deviceId),
            URLQueryItem(name: "action", value: action),
        ]
        return components.url
    }

    static func openDeviceURL(deviceId: String) -> URL? {
        var components = URLComponents()
        components.scheme = "bockmedia"
        components.host = "nowplaying"
        components.queryItems = [URLQueryItem(name: "deviceId", value: deviceId)]
        return components.url
    }

    static func canControl(item: WidgetNowPlayingItem, controlsAvailable: Bool) -> Bool {
        if item.isLocal { return true }
        guard controlsAvailable, !item.deviceId.hasPrefix("msp-") else { return false }
        let name = item.deviceName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return !name.isEmpty
    }
}
