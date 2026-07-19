import Foundation
import UserNotifications

enum FollowNotificationStore {
    private static func key(memberId: String) -> String {
        "follow_last_seen_\(memberId.isEmpty ? "default" : memberId)"
    }

    static func lastSeenAt(memberId: String?) -> String? {
        guard let raw = UserDefaults.standard.string(forKey: key(memberId: memberId ?? "")) else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    static func markSeen(memberId: String?, timestamp: String?) {
        let ts = timestamp?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !ts.isEmpty else { return }
        UserDefaults.standard.set(ts, forKey: key(memberId: memberId ?? ""))
    }

    static func markSeenFromItems(memberId: String?, timestamps: [String?]) {
        let latest = timestamps.compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.max()
        markSeen(memberId: memberId, timestamp: latest)
    }
}

enum FollowNotificationSync {
    static func checkAndNotify(repository: BockMediaRepository) async {
        let memberId = ActiveProfileStore.activeMemberId()
        let after = FollowNotificationStore.lastSeenAt(memberId: memberId)
        guard let response = try? await repository.followedNotifications(since: "30d", after: after) else { return }
        guard response.unreadCount > 0 else { return }
        await postLocalNotification(response: response)
        let timestamps = response.albums.map(\.first_seen_at) + response.tracks.map(\.first_seen_at)
        FollowNotificationStore.markSeenFromItems(memberId: memberId, timestamps: timestamps)
    }

    @MainActor
    private static func postLocalNotification(response: FollowedNotificationsResponse) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let album = response.albums.first
        let title: String
        if response.unreadCount == 1, let albumName = album?.album {
            title = "New from \(album?.artist ?? "an artist you follow")"
            _ = albumName
        } else {
            title = "\(response.unreadCount) new releases from artists you follow"
        }
        let body = album.map { "\($0.album ?? "Album") · \($0.artist ?? "")" } ?? "Tap to see what's new in your library"
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: "followed-artists-new", content: content, trigger: nil)
        try? await center.add(request)
    }
}
