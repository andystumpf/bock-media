import Foundation

struct OfflineDownloadRecord: Codable, Equatable {
    var id: String
    var title: String
    var kind: String = "playlist"
    var sourcePlaylistId: String?
}

enum OfflineDownloadSync {
    private static let defaultsKey = "offlineDownloadRegistryByMember"

    static func register(target: PlayTarget) {
        add(target.toOfflineDownloadRecord())
    }

    static func remove(collectionId: String) {
        guard let memberId = ActiveProfileStore.activeMemberId() else { return }
        var map = loadMap()
        var list = map[memberId] ?? []
        list.removeAll { $0.id == collectionId }
        map[memberId] = list
        saveMap(map)
    }

    static func visibleCollectionIds() -> Set<String> {
        guard let memberId = ActiveProfileStore.activeMemberId() else { return [] }
        return Set((loadMap()[memberId] ?? []).map(\.id))
    }

    /// Attach on-disk collections not yet assigned to any profile (legacy / pre-registry).
    static func claimOrphansForActiveProfile(store: OfflineDownloadStore = OfflineDownloadStore()) {
        guard let memberId = ActiveProfileStore.activeMemberId() else { return }
        var map = loadMap()
        let assigned = Set(map.values.flatMap { $0 }.map(\.id))
        let orphans = store.listManifests()
            .filter { !assigned.contains($0.id) }
            .map { $0.toRecord() }
        guard !orphans.isEmpty else { return }
        var list = map[memberId] ?? []
        list.append(contentsOf: orphans)
        map[memberId] = mergeRecords(list)
        saveMap(map)
    }

    static func collectionIdsForMember(_ memberId: String) -> [String] {
        (loadMap()[memberId] ?? []).map(\.id)
    }

    static func collectForMember(store: OfflineDownloadStore = OfflineDownloadStore()) -> [OfflineDownloadRecord] {
        guard let memberId = ActiveProfileStore.activeMemberId() else { return [] }
        let fromRegistry = loadMap()[memberId] ?? []
        let fromDisk = store.listManifests().map { $0.toRecord() }
        return mergeRecords(fromRegistry + fromDisk)
    }

    static func applyRemote(_ records: [OfflineDownloadRecord], repository: BockMediaRepository) {
        if let memberId = ActiveProfileStore.activeMemberId() {
            var map = loadMap()
            map[memberId] = mergeRecords(records)
            saveMap(map)
        }
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 400_000_000)
            restoreMissing(records, repository: repository)
        }
    }

    @MainActor
    static func restoreMissing(_ records: [OfflineDownloadRecord], repository: BockMediaRepository) {
        guard !records.isEmpty else { return }
        let store = OfflineDownloadStore()
        let manager = OfflineDownloadManager.shared
        manager.refresh()
        let prefs = repository.preferences
        for record in records {
            let target = record.toPlayTarget()
            if let manifest = store.readManifest(record.id), store.isCollectionComplete(manifest) { continue }
            if manager.status(for: target)?.state == .downloading { continue }
            manager.download(repository: repository, preferences: prefs, target: target)
        }
    }

    static func encode(_ records: [OfflineDownloadRecord]) -> Any? {
        guard let data = try? JSONEncoder().encode(records) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    static func decode(_ value: Any?) -> [OfflineDownloadRecord]? {
        guard let value else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode([OfflineDownloadRecord].self, from: data)
    }

    private static func add(_ record: OfflineDownloadRecord) {
        guard let memberId = ActiveProfileStore.activeMemberId() else { return }
        var map = loadMap()
        var list = map[memberId] ?? []
        list.removeAll { $0.id == record.id }
        list.append(record)
        map[memberId] = list
        saveMap(map)
    }

    private static func mergeRecords(_ records: [OfflineDownloadRecord]) -> [OfflineDownloadRecord] {
        var out: [String: OfflineDownloadRecord] = [:]
        records.forEach { out[$0.id] = $0 }
        return Array(out.values)
    }

    private static func loadMap() -> [String: [OfflineDownloadRecord]] {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey) else { return [:] }
        return (try? JSONDecoder().decode([String: [OfflineDownloadRecord]].self, from: data)) ?? [:]
    }

    private static func saveMap(_ map: [String: [OfflineDownloadRecord]]) {
        guard let data = try? JSONEncoder().encode(map) else { return }
        UserDefaults.standard.set(data, forKey: defaultsKey)
    }
}

private extension PlayTarget {
    func toOfflineDownloadRecord() -> OfflineDownloadRecord {
        OfflineDownloadRecord(
            id: downloadId(),
            title: label,
            kind: downloadKindLabel().lowercased(),
            sourcePlaylistId: {
                if case .playlist(let pid, _) = self { return pid }
                return nil
            }()
        )
    }
}

private extension OfflineDownloadRecord {
    func toPlayTarget() -> PlayTarget {
        switch kind {
        case "artist": return .artist(name: title)
        case "album": return .album(name: title, artist: nil)
        case "song": return .song(path: "", title: title)
        case "mix", "radio": return .radio(displayTitle: title, seedKind: .artist, name: title, path: nil)
        default:
            let pid = sourcePlaylistId ?? id.replacingOccurrences(of: "pl-", with: "")
            return .playlist(id: pid, name: title)
        }
    }
}

private extension OfflineCollectionManifest {
    func toRecord() -> OfflineDownloadRecord {
        OfflineDownloadRecord(id: id, title: title, kind: kind, sourcePlaylistId: sourcePlaylistId)
    }
}
