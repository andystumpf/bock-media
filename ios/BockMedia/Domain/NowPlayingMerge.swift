import Foundation

enum NowPlayingMerge {
    /// Mobile apps: Alexa/Echo rows from the server plus local phone playback (never other clients).
    static func devicesForMobile(
        remote: [NowPlayingDeviceItem],
        local: NowPlayingDeviceItem?,
        alexaDevices: [AlexaDevice]
    ) -> [NowPlayingDeviceItem] {
        let alexaOnly = remote.filter { !$0.deviceId.hasPrefix("client-") }
        if let local {
            return [local] + alexaOnly.filter { $0.deviceId != LocalPlaybackIds.localPhoneDeviceId }
        }
        return orderForDisplay(alexaOnly, alexaDevices: alexaDevices)
    }

    static func orderForDisplay(
        _ items: [NowPlayingDeviceItem],
        alexaDevices: [AlexaDevice]
    ) -> [NowPlayingDeviceItem] {
        _ = PlaybackFocus.syncPendingFocus(items: items, alexaDevices: alexaDevices)
        let sorted = items.sorted {
            if $0.paused != $1.paused { return !$0.paused }
            return ($0.deviceName ?? $0.deviceId) < ($1.deviceName ?? $1.deviceId)
        }
        guard let focusId = PlaybackFocus.focusedDeviceId,
              let focus = sorted.first(where: { $0.deviceId == focusId }) else {
            return sorted
        }
        return [focus] + sorted.filter { $0.deviceId != focusId }
    }
}
