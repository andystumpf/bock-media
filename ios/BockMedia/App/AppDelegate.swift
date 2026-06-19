import UIKit
import UserNotifications

extension Notification.Name {
    static let bockQuickAction = Notification.Name("BockQuickAction")
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        if let item = launchOptions?[.shortcutItem] as? UIApplicationShortcutItem {
            NotificationCenter.default.post(name: .bockQuickAction, object: item.type)
        }
        UNUserNotificationCenter.current().delegate = self
        DownloadNotifications.registerCategory()
        DownloadNotifications.requestAuthorization()
        // Touch the background session so any tasks restored after relaunch
        // re-attach their delegate immediately.
        _ = OfflineBackgroundSession.shared
        return true
    }

    func application(
        _ application: UIApplication,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        NotificationCenter.default.post(name: .bockQuickAction, object: shortcutItem.type)
        completionHandler(true)
    }

    /// iOS relaunches the app in the background to deliver finished download
    /// events; stash the completion handler for the URLSession delegate.
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == OfflineBackgroundSession.sessionIdentifier else {
            completionHandler()
            return
        }
        OfflineBackgroundSession.shared.backgroundCompletionHandler = completionHandler
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.actionIdentifier == DownloadNotifications.cancelActionId,
           let id = DownloadNotifications.collectionId(fromNotificationId: response.notification.request.identifier) {
            Task { @MainActor in
                OfflineDownloadManager.shared.cancelCollection(id)
            }
        }
        completionHandler()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([])
    }
}
