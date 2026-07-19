import Foundation
import UserNotifications

/// Shows a single notification per downloading collection with a Cancel action,
/// matching Android's download notification (`OfflineDownloadActionReceiver`).
enum DownloadNotifications {
    static let categoryId = "BOCK_DOWNLOAD"
    static let cancelActionId = "BOCK_DOWNLOAD_CANCEL"
    private static let prefix = "download-"

    static func registerCategory() {
        let cancel = UNNotificationAction(
            identifier: cancelActionId,
            title: "Cancel",
            options: [.destructive]
        )
        let category = UNNotificationCategory(
            identifier: categoryId,
            actions: [cancel],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    static func requestAuthorization() {
        // Avoid the system permission sheet during UI tests / README captures.
        if ProcessInfo.processInfo.arguments.contains("-UITesting")
            || ProcessInfo.processInfo.environment["UITESTING"] == "1"
        {
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func show(collectionId: String, title: String) {
        let content = UNMutableNotificationContent()
        content.title = "Downloading"
        content.body = title
        content.categoryIdentifier = categoryId
        content.sound = nil
        let request = UNNotificationRequest(
            identifier: prefix + collectionId,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    static func clear(collectionId: String) {
        let id = prefix + collectionId
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }

    static func collectionId(fromNotificationId id: String) -> String? {
        guard id.hasPrefix(prefix) else { return nil }
        return String(id.dropFirst(prefix.count))
    }
}
