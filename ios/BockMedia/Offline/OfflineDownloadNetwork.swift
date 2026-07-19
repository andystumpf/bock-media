import Foundation
import Network

@MainActor
final class OfflineDownloadNetwork: ObservableObject {
    static let shared = OfflineDownloadNetwork()

    @Published private(set) var isOnWifi = true
    private let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
    private let queue = DispatchQueue(label: "com.bockmedia.offline.network")

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.isOnWifi = path.status == .satisfied
            }
        }
        monitor.start(queue: queue)
    }

    func canDownloadNow(preferences: AppPreferences) -> Bool {
        guard preferences.downloadWifiOnly else { return true }
        return isOnWifi
    }

    func blockedReason(preferences: AppPreferences) -> String? {
        canDownloadNow(preferences: preferences) ? nil : "Downloads are Wi‑Fi only — connect to Wi‑Fi or change in Settings"
    }
}

extension AppPreferences {
    var downloadWifiOnly: Bool {
        get { UserDefaults.standard.bool(forKey: "download_wifi_only") }
        set {
            UserDefaults.standard.set(newValue, forKey: "download_wifi_only")
            objectWillChange.send()
        }
    }

    var crossfadeSeconds: Int {
        get { min(20, max(0, UserDefaults.standard.integer(forKey: "crossfade_seconds"))) }
        set {
            UserDefaults.standard.set(min(20, max(0, newValue)), forKey: "crossfade_seconds")
            objectWillChange.send()
        }
    }

    var continueAfterQueue: String {
        get { UserDefaults.standard.string(forKey: "continue_after_queue") ?? "off" }
        set {
            UserDefaults.standard.set(newValue, forKey: "continue_after_queue")
            objectWillChange.send()
        }
    }

    var nowPlayingVideo: Bool {
        get { UserDefaults.standard.bool(forKey: "now_playing_video") }
        set {
            UserDefaults.standard.set(newValue, forKey: "now_playing_video")
            objectWillChange.send()
        }
    }

    func notifyProfilePrefsApplied() {
        objectWillChange.send()
    }
}
