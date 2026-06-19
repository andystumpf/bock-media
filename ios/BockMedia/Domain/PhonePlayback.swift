import Foundation

enum PhonePlayback {
    @MainActor
    static func canPlayLocally(target: PlayTarget, remoteOk: Bool) -> Bool {
        !remoteOk || OfflineDownloadManager.shared.isDownloaded(target)
    }
}
