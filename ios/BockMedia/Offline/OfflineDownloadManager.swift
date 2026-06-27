import Combine
import Foundation

@MainActor
final class OfflineDownloadManager: ObservableObject {
    static let shared = OfflineDownloadManager()
    private static let maxTracks = 150

    @Published private(set) var statuses: [String: OfflineCollectionStatus] = [:]

    private let store = OfflineDownloadStore()
    private let session = OfflineBackgroundSession.shared
    private var prepareTasks: [String: Task<Void, Never>] = [:]
    private var cancelFlags: Set<String> = []
    private var cancellables: Set<AnyCancellable> = []
    private var reconcileCancellable: AnyCancellable?

    private weak var repository: BockMediaRepository?
    private var preferences: AppPreferences?
    private var wasOnWifi = true

    private init() {
        reconcileCancellable = NotificationCenter.default
            .publisher(for: .offlineDownloadTaskFinished)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] note in
                MainActor.assumeIsolated {
                    guard let id = note.userInfo?["collectionId"] as? String else { return }
                    self?.reconcile(collectionId: id)
                }
            }
    }

    /// Wires in the repository/preferences so the manager can resume downloads
    /// from background tasks and network-change callbacks without a live screen.
    func configure(repository: BockMediaRepository, preferences: AppPreferences) {
        self.repository = repository
        self.preferences = preferences
        wasOnWifi = OfflineDownloadNetwork.shared.isOnWifi
        cancellables.removeAll()
        OfflineDownloadNetwork.shared.$isOnWifi
            .removeDuplicates()
            .sink { [weak self] onWifi in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    let resumed = onWifi && !self.wasOnWifi
                    self.wasOnWifi = onWifi
                    if resumed { self.resumeIncomplete() }
                }
            }
            .store(in: &cancellables)
    }

    func refresh() {
        let persisted = store.listManifests().reduce(into: [String: OfflineCollectionStatus]()) { acc, manifest in
            let complete = store.isCollectionComplete(manifest)
            acc[manifest.id] = OfflineCollectionStatus(
                manifest: manifest,
                state: complete ? .complete : .failed,
                progress: complete ? 1 : store.completionProgress(manifest)
            )
        }
        let downloading = statuses.filter { $0.value.state == .downloading }
        statuses = persisted.merging(downloading) { _, new in new }
    }

    func isDownloaded(_ target: PlayTarget) -> Bool {
        statuses[target.downloadId()]?.state == .complete
    }

    func status(for target: PlayTarget) -> OfflineCollectionStatus? {
        statuses[target.downloadId()]
    }

    func download(repository: BockMediaRepository, preferences: AppPreferences, target: PlayTarget) {
        configure(repository: repository, preferences: preferences)
        OfflineDownloadSync.register(target: target)
        ClientPrefsSync.schedulePush(repository: repository)
        let id = target.downloadId()
        if statuses[id]?.state == .downloading { return }
        if let reason = OfflineDownloadNetwork.shared.blockedReason(preferences: preferences) {
            let existing = store.readManifest(id)
            statuses[id] = OfflineCollectionStatus(
                manifest: existing ?? manifestShell(id: id, target: target, tracks: []),
                state: .failed,
                progress: existing.map { store.completionProgress($0) } ?? 0,
                error: reason
            )
            return
        }
        cancelFlags.remove(id)
        prepareTasks[id]?.cancel()
        prepareTasks[id] = Task {
            await prepareAndEnqueue(repository: repository, target: target, resyncOnly: false)
            prepareTasks[id] = nil
        }
    }

    func cancelCollection(_ id: String) {
        cancelFlags.insert(id)
        prepareTasks[id]?.cancel()
        prepareTasks[id] = nil
        session.cancel(collectionId: id)
        if var status = statuses[id], status.state == .downloading {
            status.state = .failed
            status.error = "Cancelled"
            statuses[id] = status
        }
        DownloadNotifications.clear(collectionId: id)
        cancelFlags.remove(id)
    }

    func deleteCollection(_ id: String) {
        session.cancel(collectionId: id)
        store.deleteCollection(id)
        statuses.removeValue(forKey: id)
        DownloadNotifications.clear(collectionId: id)
        OfflineDownloadSync.remove(collectionId: id)
        if let repository {
            ClientPrefsSync.schedulePush(repository: repository)
        }
    }

    func retry(repository: BockMediaRepository, preferences: AppPreferences, id: String) {
        guard let status = statuses[id] else { return }
        download(repository: repository, preferences: preferences, target: status.manifest.toPlayTarget())
    }

    func resync(repository: BockMediaRepository, preferences: AppPreferences, target: PlayTarget) {
        guard OfflineDownloadNetwork.shared.canDownloadNow(preferences: preferences) else { return }
        configure(repository: repository, preferences: preferences)
        let id = target.downloadId()
        prepareTasks[id]?.cancel()
        prepareTasks[id] = Task {
            await prepareAndEnqueue(repository: repository, target: target, resyncOnly: true)
            prepareTasks[id] = nil
        }
    }

    /// Re-enqueues any missing tracks for incomplete collections. Invoked from the
    /// background-refresh task and when Wi‑Fi connectivity returns.
    func resumeIncomplete() {
        guard let repository, let preferences,
              OfflineDownloadNetwork.shared.canDownloadNow(preferences: preferences) else { return }
        for manifest in store.listManifests() where !store.isCollectionComplete(manifest) {
            let id = manifest.id
            if statuses[id]?.state == .downloading { continue }
            if session.pendingCount(collectionId: id) > 0 { continue }
            prepareTasks[id]?.cancel()
            prepareTasks[id] = Task {
                await prepareAndEnqueue(repository: repository, target: manifest.toPlayTarget(), resyncOnly: true)
                prepareTasks[id] = nil
            }
        }
    }

    private func prepareAndEnqueue(repository: BockMediaRepository, target: PlayTarget, resyncOnly: Bool) async {
        let id = target.downloadId()
        let existing = store.readManifest(id)
        statuses[id] = OfflineCollectionStatus(
            manifest: existing ?? manifestShell(id: id, target: target, tracks: []),
            state: .downloading,
            progress: existing.map { store.completionProgress($0) } ?? 0
        )

        do {
            let resolved = try await LocalPlaybackQueueResolver.resolve(
                repository: repository, target: target, maxTracks: Self.maxTracks
            )
            guard !resolved.isEmpty else { throw LocalPlaybackError.noTracks }

            let merged = store.mergeTrackEntries(existing: existing, resolved: resolved, collectionId: id)
            guard !merged.isEmpty else { throw LocalPlaybackError.noTracks }

            var manifest = manifestShell(id: id, target: target, tracks: merged)
            manifest.tracks = merged
            manifest.coverArtPath = merged.first?.path ?? manifest.coverArtPath
            try store.saveManifest(manifest)

            if cancelFlags.contains(id) { throw CancellationError() }

            let missing = merged.filter { store.resolveTrackFile(manifest: manifest, entry: $0) == nil }
            if missing.isEmpty {
                finalizeComplete(id: id, manifest: manifest)
                return
            }
            _ = resyncOnly

            guard let base = try? await repository.resolveBaseURL() else {
                throw LocalPlaybackError.missingStreamURL
            }

            for entry in missing {
                guard let urlString = ServerURL.streamURL(base: base, filepath: entry.path),
                      let url = URL(string: urlString) else { continue }
                var request = URLRequest(url: url)
                AuthHeaders.apply(
                    to: &request,
                    localHosts: repository.preferences.localHosts(),
                    username: repository.preferences.adminUser,
                    password: repository.preferences.adminPass,
                    token: repository.preferences.mobileToken
                )
                let dest = store.trackFile(collectionId: id, fileName: entry.fileName)
                session.enqueue(
                    OfflinePendingDownload(
                        collectionId: id, path: entry.path, fileName: entry.fileName, destPath: dest.path
                    ),
                    request: request
                )
            }

            statuses[id] = OfflineCollectionStatus(
                manifest: manifest,
                state: .downloading,
                progress: store.completionProgress(manifest)
            )
            DownloadNotifications.show(collectionId: id, title: manifest.title)
        } catch is CancellationError {
            markFailed(id: id, target: target, error: "Cancelled")
        } catch {
            markFailed(id: id, target: target, error: error.localizedDescription)
        }
    }

    /// Recomputes a collection's status after a background task finishes.
    private func reconcile(collectionId id: String) {
        guard let manifest = store.readManifest(id) else { return }
        if store.isCollectionComplete(manifest) {
            finalizeComplete(id: id, manifest: manifest)
            return
        }
        let pending = session.pendingCount(collectionId: id)
        if pending > 0 {
            statuses[id] = OfflineCollectionStatus(
                manifest: manifest,
                state: .downloading,
                progress: store.completionProgress(manifest)
            )
        } else {
            statuses[id] = OfflineCollectionStatus(
                manifest: manifest,
                state: .failed,
                progress: store.completionProgress(manifest),
                error: "Some tracks failed to download"
            )
            DownloadNotifications.clear(collectionId: id)
        }
    }

    private func finalizeComplete(id: String, manifest: OfflineCollectionManifest) {
        var done = manifest
        done.lastSyncedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
        try? store.saveManifest(done)
        store.pruneOrphanFiles(collectionId: id, tracks: done.tracks)
        statuses[id] = OfflineCollectionStatus(manifest: done, state: .complete, progress: 1)
        DownloadNotifications.clear(collectionId: id)
        if let repository {
            ClientPrefsSync.schedulePush(repository: repository)
        }
    }

    private func markFailed(id: String, target: PlayTarget, error: String) {
        let partial = store.readManifest(id)
        statuses[id] = OfflineCollectionStatus(
            manifest: partial ?? manifestShell(id: id, target: target, tracks: []),
            state: .failed,
            progress: partial.map { store.completionProgress($0) } ?? 0,
            error: error
        )
        DownloadNotifications.clear(collectionId: id)
    }

    private func manifestShell(id: String, target: PlayTarget, tracks: [OfflineTrackEntry]) -> OfflineCollectionManifest {
        OfflineCollectionManifest(
            id: id,
            title: target.label,
            kind: target.downloadKindLabel().lowercased(),
            sourcePlaylistId: {
                if case .playlist(let pid, _) = target { return pid }
                return nil
            }(),
            coverArtPath: tracks.first?.path,
            tracks: tracks
        )
    }
}
