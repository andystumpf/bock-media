import Foundation

final class OfflineDownloadStore {
    private let collectionsRoot: URL
    private let legacyRoot: URL
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("offline", isDirectory: true)
        collectionsRoot = base.appendingPathComponent("collections", isDirectory: true)
        legacyRoot = base.appendingPathComponent("playlists", isDirectory: true)
        try? FileManager.default.createDirectory(at: collectionsRoot, withIntermediateDirectories: true)
    }

    func collectionDir(_ id: String) -> URL {
        collectionsRoot.appendingPathComponent(id, isDirectory: true)
    }

    func trackFile(collectionId: String, fileName: String) -> URL {
        collectionDir(collectionId).appendingPathComponent(fileName)
    }

    func manifestFile(collectionId: String) -> URL {
        collectionDir(collectionId).appendingPathComponent("manifest.json")
    }

    func listManifests() -> [OfflineCollectionManifest] {
        var out = readAll(from: collectionsRoot)
        let legacy = readAll(from: legacyRoot).map { m in
            m.id.hasPrefix("pl-") ? m : OfflineCollectionManifest(
                id: "pl-\(m.id)", title: m.title, kind: m.kind,
                sourcePlaylistId: m.id, downloadedAtMs: m.downloadedAtMs, tracks: m.tracks
            )
        }
        for m in legacy where !out.contains(where: { $0.id == m.id }) {
            out.append(m)
        }
        return out.sorted { $0.downloadedAtMs > $1.downloadedAtMs }
    }

    func readManifest(_ collectionId: String) -> OfflineCollectionManifest? {
        readManifest(collectionId, root: collectionsRoot) ?? readManifest(collectionId, root: legacyRoot)
    }

    func saveManifest(_ manifest: OfflineCollectionManifest) throws {
        let dir = collectionDir(manifest.id)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let data = try encoder.encode(manifest)
        try data.write(to: manifestFile(collectionId: manifest.id), options: .atomic)
    }

    func deleteCollection(_ collectionId: String) {
        try? FileManager.default.removeItem(at: collectionDir(collectionId))
        try? FileManager.default.removeItem(at: legacyRoot.appendingPathComponent(collectionId.replacingOccurrences(of: "pl-", with: "")))
    }

    func resolveTrackFile(manifest: OfflineCollectionManifest, entry: OfflineTrackEntry) -> URL? {
        let file = trackFile(collectionId: manifest.id, fileName: entry.fileName)
        if FileManager.default.fileExists(atPath: file.path),
           (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) ?? 0 > 0 {
            return file
        }
        let legacyId = manifest.legacyPlaylistId ?? manifest.id.replacingOccurrences(of: "pl-", with: "")
        let legacy = legacyRoot.appendingPathComponent(legacyId).appendingPathComponent(entry.fileName)
        if FileManager.default.fileExists(atPath: legacy.path),
           (try? legacy.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) ?? 0 > 0 {
            return legacy
        }
        return nil
    }

    func localFileURL(path: String, collectionId: String? = nil) -> URL? {
        let manifests: [OfflineCollectionManifest]
        if let collectionId, let m = readManifest(collectionId) {
            manifests = [m]
        } else {
            manifests = listManifests()
        }
        for manifest in manifests {
            guard let entry = manifest.tracks.first(where: { $0.path == path }) else { continue }
            if let url = resolveTrackFile(manifest: manifest, entry: entry) { return url }
        }
        return nil
    }

    func localTracks(for manifest: OfflineCollectionManifest) -> [LocalTrack] {
        manifest.tracks.compactMap { entry in
            guard let fileURL = resolveTrackFile(manifest: manifest, entry: entry) else { return nil }
            return LocalTrack(
                path: entry.path, title: entry.title, artist: entry.artist, album: entry.album,
                streamURL: fileURL, localFileURL: fileURL
            )
        }
    }

    func completionProgress(_ manifest: OfflineCollectionManifest) -> Float {
        guard !manifest.tracks.isEmpty else { return 0 }
        let done = manifest.tracks.filter { resolveTrackFile(manifest: manifest, entry: $0) != nil }.count
        return Float(done) / Float(manifest.tracks.count)
    }

    func isCollectionComplete(_ manifest: OfflineCollectionManifest) -> Bool {
        !manifest.tracks.isEmpty && manifest.tracks.allSatisfy { resolveTrackFile(manifest: manifest, entry: $0) != nil }
    }

    func bytesOnDisk() -> Int64 {
        dirSize(collectionsRoot) + dirSize(legacyRoot)
    }

    func collectionBytes(_ collectionId: String) -> Int64 {
        dirSize(collectionDir(collectionId))
    }

    func mergeTrackEntries(
        existing: OfflineCollectionManifest?,
        resolved: [LocalTrack],
        collectionId: String
    ) -> [OfflineTrackEntry] {
        var byPath: [String: OfflineTrackEntry] = [:]
        var usedNames = Set<String>()
        existing?.tracks.forEach { entry in
            byPath[entry.path] = entry
            usedNames.insert(entry.fileName)
        }
        var seq = existing?.tracks.count ?? 0
        for track in resolved where !track.path.isEmpty {
            if let prior = byPath[track.path] {
                byPath[track.path] = prior
                continue
            }
            let fileName = uniqueOfflineFileName(path: track.path, index: seq, used: &usedNames)
            seq += 1
            byPath[track.path] = OfflineTrackEntry(
                path: track.path, title: track.title, artist: track.artist, album: track.album, fileName: fileName
            )
        }
        return Array(byPath.values)
    }

    func pruneOrphanFiles(collectionId: String, tracks: [OfflineTrackEntry]) {
        let dir = collectionDir(collectionId)
        guard let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        let keep = Set(tracks.map(\.fileName) + ["manifest.json"])
        for file in files where file.lastPathComponent != "manifest.json" && !keep.contains(file.lastPathComponent) {
            try? FileManager.default.removeItem(at: file)
        }
    }

    private func readAll(from root: URL) -> [OfflineCollectionManifest] {
        guard let dirs = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey]) else {
            return []
        }
        return dirs.compactMap { url -> OfflineCollectionManifest? in
            guard (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true else { return nil }
            return readManifest(url.lastPathComponent, root: root)
        }
    }

    private func readManifest(_ id: String, root: URL) -> OfflineCollectionManifest? {
        let file = root.appendingPathComponent(id).appendingPathComponent("manifest.json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? decoder.decode(OfflineCollectionManifest.self, from: data)
    }

    private func dirSize(_ dir: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else {
            return 0
        }
        var total: Int64 = 0
        for case let url as URL in enumerator {
            total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return total
    }
}

private func offlineTrackFileName(path: String, index: Int) -> String {
    let base = (path as NSString).lastPathComponent
        .replacingOccurrences(of: #"[^A-Za-z0-9._-]"#, with: "_", options: .regularExpression)
        .prefix(60)
    let ext = (path as NSString).pathExtension.isEmpty ? "mp3" : (path as NSString).pathExtension
    let pathTag = String(format: "%08x", abs(path.hashValue))
    let baseStr = base.isEmpty ? "track" : String(base)
    return String(format: "%04d_%@_%@.%@", index, pathTag, baseStr, ext)
}

private func uniqueOfflineFileName(path: String, index: Int, used: inout Set<String>) -> String {
    var idx = index
    while true {
        let candidate = offlineTrackFileName(path: path, index: idx)
        if !used.contains(candidate) {
            used.insert(candidate)
            return candidate
        }
        idx += 1
    }
}
