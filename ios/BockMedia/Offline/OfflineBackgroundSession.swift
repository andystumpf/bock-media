import Foundation

/// Metadata for one in-flight background download, persisted so tasks can be
/// reconciled after the app is relaunched by the system.
struct OfflinePendingDownload: Codable {
    let collectionId: String
    let path: String
    let fileName: String
    let destPath: String
}

/// Wraps a `URLSessionConfiguration.background` session so offline downloads
/// continue while the app is backgrounded or terminated, mirroring Android's
/// foreground-service + WorkManager download pipeline.
///
/// The delegate runs off the main thread; completion is surfaced to the
/// (`@MainActor`) `OfflineDownloadManager` via `.offlineDownloadTaskFinished`.
final class OfflineBackgroundSession: NSObject {
    static let shared = OfflineBackgroundSession()
    static let sessionIdentifier = "com.bockmedia.console.downloads"

    /// Stored by the AppDelegate when iOS relaunches us for background events.
    var backgroundCompletionHandler: (() -> Void)?

    private let mapURL: URL
    private let lock = NSLock()
    private var taskMap: [Int: OfflinePendingDownload] = [:]

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.httpMaximumConnectionsPerHost = 4
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    private override init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("offline", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        mapURL = base.appendingPathComponent("bg_tasks.json")
        super.init()
        loadMap()
        // Touch the lazy session so the delegate is attached and any tasks the
        // system restored after relaunch start delivering events again.
        _ = session
    }

    /// Number of tasks still in flight for a collection (used to detect failure
    /// vs still-downloading during reconciliation).
    func pendingCount(collectionId: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        return taskMap.values.filter { $0.collectionId == collectionId }.count
    }

    func hasAnyPending() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return !taskMap.isEmpty
    }

    func enqueue(_ pending: OfflinePendingDownload, request: URLRequest) {
        let task = session.downloadTask(with: request)
        lock.lock()
        taskMap[task.taskIdentifier] = pending
        persistLocked()
        lock.unlock()
        task.resume()
    }

    func cancel(collectionId: String) {
        session.getAllTasks { tasks in
            self.lock.lock()
            let ids = self.taskMap.filter { $0.value.collectionId == collectionId }.map { $0.key }
            self.lock.unlock()
            for task in tasks where ids.contains(task.taskIdentifier) {
                task.cancel()
            }
        }
    }

    private func removePending(_ taskId: Int) -> OfflinePendingDownload? {
        lock.lock(); defer { lock.unlock() }
        let pending = taskMap.removeValue(forKey: taskId)
        persistLocked()
        return pending
    }

    private func loadMap() {
        guard let data = try? Data(contentsOf: mapURL),
              let decoded = try? JSONDecoder().decode([Int: OfflinePendingDownload].self, from: data) else { return }
        taskMap = decoded
    }

    private func persistLocked() {
        if let data = try? JSONEncoder().encode(taskMap) {
            try? data.write(to: mapURL, options: .atomic)
        }
    }
}

extension OfflineBackgroundSession: URLSessionDownloadDelegate {
    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let pending = peek(downloadTask.taskIdentifier) else { return }
        let http = downloadTask.response as? HTTPURLResponse
        let ok = http.map { (200..<300).contains($0.statusCode) } ?? true
        guard ok else { return }
        let dest = URL(fileURLWithPath: pending.destPath)
        try? FileManager.default.createDirectory(
            at: dest.deletingLastPathComponent(), withIntermediateDirectories: true
        )
        try? FileManager.default.removeItem(at: dest)
        // The temp file is only valid inside this callback; move it synchronously.
        try? FileManager.default.moveItem(at: location, to: dest)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let pending = removePending(task.taskIdentifier) else { return }
        let success = error == nil
        NotificationCenter.default.post(
            name: .offlineDownloadTaskFinished,
            object: nil,
            userInfo: ["collectionId": pending.collectionId, "success": success]
        )
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletionHandler?()
            self.backgroundCompletionHandler = nil
        }
    }

    private func peek(_ taskId: Int) -> OfflinePendingDownload? {
        lock.lock(); defer { lock.unlock() }
        return taskMap[taskId]
    }
}

extension Notification.Name {
    static let offlineDownloadTaskFinished = Notification.Name("com.bockmedia.offlineDownloadTaskFinished")
}
