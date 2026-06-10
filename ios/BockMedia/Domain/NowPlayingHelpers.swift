import Foundation

func alexaControlsAvailable(_ status: AlexaRemoteStatus?) -> Bool {
    status?.configured == true && status?.authenticated == true
}

func resolveSerial(device: NowPlayingDeviceItem, alexaDevices: [AlexaDevice]) -> String? {
    guard let name = device.deviceName?.lowercased() else { return nil }
    return alexaDevices.first(where: { ($0.name ?? "").lowercased() == name })?.serial
}

func canControlDevice(
    _ device: NowPlayingDeviceItem,
    alexaDevices: [AlexaDevice],
    controlsAvailable: Bool,
    remoteOk: Bool
) -> Bool {
    if device.deviceId == LocalPlaybackIds.localPhoneDeviceId { return true }
    if !controlsAvailable || !remoteOk { return false }
    if device.deviceId.hasPrefix("msp-") { return false }
    if device.deviceName?.isEmpty != false { return false }
    return resolveSerial(device: device, alexaDevices: alexaDevices) != nil
}
