import BackgroundTasks
import Foundation

enum BackgroundDownloadScheduler {
    static let taskIdentifier = "com.bockmedia.console.offline-refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            refresh.expirationHandler = { refresh.setTaskCompleted(success: false) }
            Task { @MainActor in
                OfflineDownloadManager.shared.refresh()
                OfflineDownloadManager.shared.resumeIncomplete()
                refresh.setTaskCompleted(success: true)
                schedule()
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
