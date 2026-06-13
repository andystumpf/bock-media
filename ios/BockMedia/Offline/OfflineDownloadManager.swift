import Foundation

@MainActor
final class OfflineDownloadManager: ObservableObject {
    static let shared = OfflineDownloadManager()
    private static let maxTracks = 150

    @Published private(set) var statuses: [String: OfflineCollectionStatus] = [:]

    private let store = OfflineDownloadStore()
    private var activeTasks: [String: Task<Void, Never>] = [:]
    private var cancelFlags: Set<String> = []

    private init() {}

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
        activeTasks[id]?.cancel()
        activeTasks[id] = Task {
            await downloadLocked(repository: repository, target: target, resyncOnly: false)
            activeTasks[id] = nil
        }
    }

    func cancelCollection(_ id: String) {
        cancelFlags.insert(id)
        activeTasks[id]?.cancel()
        if var status = statuses[id], status.state == .downloading {
            status.state = .failed
            status.error = "Cancelled"
            statuses[id] = status
        }
        cancelFlags.remove(id)
        activeTasks[id] = nil
    }

    func deleteCollection(_ id: String) {
        store.deleteCollection(id)
        statuses.removeValue(forKey: id)
    }

    func retry(repository: BockMediaRepository, preferences: AppPreferences, id: String) {
        guard let status = statuses[id] else { return }
        download(repository: repository, preferences: preferences, target: status.manifest.toPlayTarget())
    }

    func resync(repository: BockMediaRepository, preferences: AppPreferences, target: PlayTarget) {
        guard OfflineDownloadNetwork.shared.canDownloadNow(preferences: preferences) else { return }
        let id = target.downloadId()
        activeTasks[id] = Task {
            await downloadLocked(repository: repository, target: target, resyncOnly: true)
            activeTasks[id] = nil
        }
    }

    private func downloadLocked(repository: BockMediaRepository, target: PlayTarget, resyncOnly: Bool) async {
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

            let shell = manifestShell(id: id, target: target, tracks: merged)
            if resyncOnly && merged.allSatisfy({ store.resolveTrackFile(manifest: shell, entry: $0) != nil }) {
                var manifest = shell
                manifest.lastSyncedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
                try store.saveManifest(manifest)
                statuses[id] = OfflineCollectionStatus(manifest: manifest, state: .complete, progress: 1)
                return
            }

            guard let base = try? await repository.resolveBaseURL() else { throw LocalPlaybackError.missingStreamURL }

            var done: [OfflineTrackEntry] = []
            for entry in merged {
                try Task.checkCancellation()
                if cancelFlags.contains(id) { throw CancellationError() }

                if let existingFile = store.resolveTrackFile(manifest: shell, entry: entry) {
                    _ = existingFile
                    done.append(entry)
                    try publishProgress(shell: shell, done: done, all: merged)
                    continue
                }

                guard let urlString = ServerURL.streamURL(base: base, filepath: entry.path),
                      let url = URL(string: urlString) else { throw LocalPlaybackError.missingStreamURL }

                var request = URLRequest(url: url)
                AuthHeaders.apply(
                    to: &request,
                    localHosts: repository.preferences.localHosts(),
                    username: repository.preferences.adminUser,
                    password: repository.preferences.adminPass,
                    token: repository.preferences.mobileToken
                )

                let dest = store.trackFile(collectionId: id, fileName: entry.fileName)
                try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
                let (tempURL, response) = try await URLSession.shared.download(for: request)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw URLError(.badServerResponse)
                }
                if FileManager.default.fileExists(atPath: dest.path) {
                    try FileManager.default.removeItem(at: dest)
                }
                try FileManager.default.moveItem(at: tempURL, to: dest)
                done.append(entry)
                try publishProgress(shell: shell, done: done, all: merged)
            }

            var manifest = shell
            manifest.tracks = merged
            manifest.lastSyncedAtMs = Int64(Date().timeIntervalSince1970 * 1000)
            try store.saveManifest(manifest)
            store.pruneOrphanFiles(collectionId: id, tracks: merged)
            statuses[id] = OfflineCollectionStatus(manifest: manifest, state: .complete, progress: 1)
        } catch is CancellationError {
            if var status = statuses[id] {
                status.state = .failed
                status.error = "Cancelled"
                statuses[id] = status
            }
        } catch {
            let partial = store.readManifest(id)
            statuses[id] = OfflineCollectionStatus(
                manifest: partial ?? manifestShell(id: id, target: target, tracks: []),
                state: .failed,
                progress: partial.map { store.completionProgress($0) } ?? 0,
                error: error.localizedDescription
            )
        }
    }

    private func publishProgress(shell: OfflineCollectionManifest, done: [OfflineTrackEntry], all: [OfflineTrackEntry]) throws {
        var manifest = shell
        manifest.tracks = all
        manifest.coverArtPath = done.first?.path ?? manifest.coverArtPath
        try store.saveManifest(manifest)
        statuses[manifest.id] = OfflineCollectionStatus(
            manifest: manifest,
            state: .downloading,
            progress: Float(done.count) / Float(max(all.count, 1))
        )
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
