import Foundation
import UIKit

/// Syncs phone settings and preferences to the server (per household profile).
@MainActor
enum ClientPrefsSync {
    private static weak var repository: BockMediaRepository?
    private static var pushTask: Task<Void, Never>?
    private static var pulling = false

    static func configure(repository: BockMediaRepository) {
        self.repository = repository
    }

    static func schedulePush() {
        guard let repository else { return }
        schedulePush(repository: repository)
    }

    static func schedulePush(repository: BockMediaRepository) {
        pushTask?.cancel()
        pushTask = Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            try? await push(repository: repository)
        }
    }

    static func pullAndApply(repository: BockMediaRepository) async {
        if pulling { return }
        pulling = true
        defer { pulling = false }
        let prefs = repository.preferences
        let clientId = ClientIdStore.clientId()
        await rebindFromPhone(repository: repository, clientId: clientId)
        let restored = await restoreActiveMember(repository: repository, clientId: clientId)
        let memberId = ActiveProfileStore.activeMemberId()
        guard let remote = try? await repository.clientPrefs(clientId: clientId, memberId: memberId) else { return }
        applyMerged(prefs: prefs, merged: remote.mergedDict, repository: repository)
        if let memberId, !memberId.isEmpty {
            try? await repository.bindClient(clientId: clientId, memberId: memberId, phoneId: InstallIdentity.phoneId())
        }
        if restored || shouldRefreshHomeForProfile() {
            HomeFeedCache.invalidate()
            HomeLoadCoordinator.resetReloadWindow()
        }
    }

    static func ensureProfileLinked(repository: BockMediaRepository) async -> Bool {
        await restoreActiveMember(repository: repository, clientId: ClientIdStore.clientId())
    }

    static func onActiveMemberChanged(repository: BockMediaRepository, memberId: String?) async {
        let clientId = ClientIdStore.clientId()
        HomeFeedCache.invalidate()
        HomeLoadCoordinator.resetReloadWindow()
        try? await repository.bindClient(clientId: clientId, memberId: memberId, phoneId: InstallIdentity.phoneId())
        try? await push(repository: repository)
        await pullAndApply(repository: repository)
    }

    static func push(repository: BockMediaRepository) async throws {
        let prefs = repository.preferences
        let clientId = ClientIdStore.clientId()
        let memberId = ActiveProfileStore.activeMemberId()
        _ = try await repository.putClientPrefs(
            clientId: clientId,
            memberId: memberId,
            memberPrefs: collectMemberPrefs(prefs: prefs, memberId: memberId),
            clientPrefs: [:]
        )
    }

    // MARK: - Private

    private static func rebindFromPhone(repository: BockMediaRepository, clientId: String) async {
        if ActiveProfileStore.activeMemberId() != nil { return }
        let phoneId = InstallIdentity.phoneId()
        guard !phoneId.isEmpty else { return }
        let model = UIDevice.current.model.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = model.isEmpty ? "This phone" : "iOS · \(model)"
        guard let memberId = try? await repository.connectInstall(
            phoneId: phoneId,
            deviceName: label,
            clientId: clientId
        ), !memberId.isEmpty else { return }
        ActiveProfileStore.setActiveMember(memberId)
    }

    private static func restoreActiveMember(repository: BockMediaRepository, clientId: String) async -> Bool {
        if ActiveProfileStore.activeMemberId() != nil { return false }
        guard let household = try? await repository.household() else { return false }
        let deviceId = repository.clientDeviceId()
        let fromBinding = household.clientBindings.first(where: {
            $0.clientDeviceId == deviceId || $0.clientDeviceId == clientId
        })?.memberId?.nilIfBlank
        if let fromBinding {
            ActiveProfileStore.setActiveMember(fromBinding)
            return true
        }
        let members = household.members
        if members.isEmpty { return false }
        if members.count == 1, let only = members[0].id.nilIfBlank {
            ActiveProfileStore.setActiveMember(only)
            return true
        }
        return false
    }

    private static func shouldRefreshHomeForProfile() -> Bool {
        ActiveProfileStore.activeMemberId() != nil
    }

    private static func collectMemberPrefs(prefs: AppPreferences, memberId: String?) -> [String: Any] {
        var out: [String: Any] = [
            "searchAllLibraries": prefs.searchAllLibraries,
            "downloadWifiOnly": prefs.downloadWifiOnly,
            "crossfadeSeconds": prefs.crossfadeSeconds,
            "continueAfterQueue": prefs.continueAfterQueue,
            "rememberMe": prefs.rememberMe,
        ]
        if let path = prefs.searchSourcePath {
            out["searchSourcePath"] = path
        }
        if let memberId, !memberId.isEmpty {
            out["activeMemberId"] = memberId
        }
        let selections = SearchHistoryStore.selections()
        if !selections.isEmpty, let encoded = encodeSelections(selections) {
            out["searchSelections"] = encoded
        }
        if let engagement = HomeTileEngagement.exportJson() {
            out["homeTileEngagement"] = engagement
        }
        if let memberId, !memberId.isEmpty {
            if let last = prefs.lastDevice {
                out["lastDevice"] = last
            }
            let pinned = PinnedDevicesStore.pinned()
            if !pinned.isEmpty {
                out["pinnedDevices"] = pinned
            }
            let offline = OfflineDownloadSync.collectForMember()
            if !offline.isEmpty, let encoded = OfflineDownloadSync.encode(offline) {
                out["offlineDownloads"] = encoded
            }
        }
        return out
    }

    static func applyMerged(prefs: AppPreferences, merged: [String: Any], repository: BockMediaRepository? = nil) {
        if merged.isEmpty { return }
        if let v = merged["searchAllLibraries"] as? Bool {
            prefs.searchAllLibraries = v
        }
        if let path = merged["searchSourcePath"] as? String, !path.isEmpty {
            prefs.searchSourcePath = path
        } else if merged.keys.contains("searchSourcePath") {
            prefs.searchSourcePath = nil
        }
        if let v = merged["downloadWifiOnly"] as? Bool {
            prefs.downloadWifiOnly = v
        }
        if let v = merged["crossfadeSeconds"] as? Int {
            prefs.crossfadeSeconds = v
        } else if let v = merged["crossfadeSeconds"] as? NSNumber {
            prefs.crossfadeSeconds = v.intValue
        }
        if let v = merged["continueAfterQueue"] as? String {
            prefs.continueAfterQueue = v
        }
        if let v = merged["rememberMe"] as? Bool {
            prefs.rememberMe = v
        }
        if ActiveProfileStore.activeMemberId() == nil,
           let id = merged["activeMemberId"] as? String, !id.isEmpty {
            ActiveProfileStore.setActiveMember(id)
        }
        if let items = decodeSelections(merged["searchSelections"]) {
            SearchHistoryStore.replaceSelections(items)
        }
        if let raw = merged["homeTileEngagement"] as? String {
            HomeTileEngagement.importJson(raw)
        }
        if let last = merged["lastDevice"] as? String, !last.isEmpty {
            prefs.lastDevice = last
        }
        if let pinned = decodeStringList(merged["pinnedDevices"]) {
            PinnedDevicesStore.setPinned(pinned)
        }
        if let repository, let records = OfflineDownloadSync.decode(merged["offlineDownloads"]) {
            OfflineDownloadSync.applyRemote(records, repository: repository)
        }
    }

    private static func encodeSelections(_ items: [SearchRecentSelection]) -> Any? {
        guard let data = try? JSONEncoder().encode(items) else { return nil }
        return try? JSONSerialization.jsonObject(with: data)
    }

    private static func decodeSelections(_ value: Any?) -> [SearchRecentSelection]? {
        guard let value else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode([SearchRecentSelection].self, from: data)
    }

    private static func decodeStringList(_ value: Any?) -> [String]? {
        guard let arr = value as? [Any] else { return nil }
        let strings = arr.compactMap { ($0 as? String)?.nilIfBlank }
        return strings.isEmpty ? nil : strings
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
