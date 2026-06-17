import Foundation

enum PhonePlayback {
    static func canPlayLocally(target: PlayTarget, remoteOk: Bool) -> Bool {
        !remoteOk || OfflineDownloadManager.shared.isDownloaded(target)
    }
}
