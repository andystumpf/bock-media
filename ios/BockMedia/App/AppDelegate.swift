import UIKit

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
}
