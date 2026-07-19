import Foundation
import UIKit

enum ClientPrefsSyncNotifications {
    static let profileChanged = Notification.Name("BockProfileChanged")
    static let prefsApplied = Notification.Name("BockPrefsApplied")
}

/// Syncs phone settings and preferences to the server (per household profile).
@MainActor
enum ClientPrefsSync {
    private static weak var repository: BockMediaRepository?
    private static var pushTask: Task<Void, Never>?
    private static var pulling = false
    private static var lastPullCompletedMs: Int64 = 0
    private(set) static var profileChangeRevision = 0

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

    static func markBootPullCompleted() {
        lastPullCompletedMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func shouldSkipResumePull() -> Bool {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return HomeLoadCoordinator.shouldSkipResumePull(
            lastPullCompletedMs: lastPullCompletedMs,
            nowMs: now
        )
    }

    static func pullAndApply(repository: BockMediaRepository, profileSwitch: Bool = false) async {
        if pulling {
            if profileSwitch {
                while pulling {
                    try? await Task.sleep(nanoseconds: 100_000_000)
                }
            } else {
                _ = await HouseholdStore.refresh(repository: repository, force: true)
                AutomationSessionCache.invalidate()
                return
            }
        }
        pulling = true
        defer { pulling = false }
        let prefs = repository.preferences
        let clientId = ClientIdStore.clientId()
        let profileAdjusted = await syncHouseholdProfile(repository: repository, clientId: clientId, profileSwitch: profileSwitch)
        let memberId = ActiveProfileStore.activeMemberId()
        if let remote = try? await repository.clientPrefs(clientId: clientId, memberId: memberId) {
            // When a profile is active, read profile-scoped prefs from the member bucket (not stale client merge).
            let merged = (profileSwitch || memberId != nil) ? remote.memberPrefsDict : remote.mergedDict
            if profileAdjusted, ActiveProfileStore.activeMemberId() == nil,
               !ActiveProfileStore.hasProfileChoice(),
               let fromPrefs = merged["activeMemberId"] as? String,
               HouseholdStore.memberExists(fromPrefs) {
                ActiveProfileStore.setActiveMember(fromPrefs)
            }
            applyMerged(prefs: prefs, merged: merged, repository: repository, profileSwitch: profileSwitch)
        }
        if let memberId = ActiveProfileStore.activeMemberId(), !memberId.isEmpty {
            try? await repository.bindClient(clientId: clientId, memberId: memberId, phoneId: InstallIdentity.phoneId())
        }
        repository.clearRatingsCache()
        if profileSwitch || shouldRefreshHomeForProfile(
            activeProfileLinked: ActiveProfileStore.activeMemberId() != nil,
            feed: HomeFeedCache.peek(),
            hasRatedSongs: HomeFeedCache.peekHasRatedSongs()
        ) {
            HomeFeedCache.invalidate()
            HomeLoadCoordinator.resetReloadWindow()
        }
        lastPullCompletedMs = Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// No-op — profile is chosen explicitly in ProfilePickerGate or Family.
    static func ensureProfileLinked(repository: BockMediaRepository) async -> Bool { false }

    static func onActiveMemberChanged(
        repository: BockMediaRepository,
        memberId: String?,
        previousMemberId: String? = nil
    ) async {
        pushTask?.cancel()
        pushTask = nil
        let clientId = ClientIdStore.clientId()
        if let memberId, !memberId.isEmpty {
            ActiveProfileStore.setActiveMember(memberId)
        } else {
            ActiveProfileStore.chooseUnattributed()
        }
        if let previousMemberId, !previousMemberId.isEmpty {
            try? await push(repository: repository, memberIdOverride: previousMemberId)
        }
        try? await repository.bindClient(clientId: clientId, memberId: memberId, phoneId: InstallIdentity.phoneId())
        await pullAndApply(repository: repository, profileSwitch: true)

        OfflineDownloadManager.shared.onActiveProfileChanged(previousMemberId: previousMemberId)
        OfflineDownloadSync.claimOrphansForActiveProfile()
        repository.clearRatingsCache()
        HomeFeedCache.invalidate()
        HomeLoadCoordinator.resetReloadWindow()
        LibrarySessionCache.invalidate()
        LibraryCachePersistence.clear()
        SessionDataStore.invalidatePlaylists()
        SearchBrowseSessionCache.invalidate()
        SearchQueryCache.invalidate()
        SearchResultsSessionCache.clear()
        AutomationSessionCache.invalidate()
        profileChangeRevision += 1
        NotificationCenter.default.post(name: ClientPrefsSyncNotifications.profileChanged, object: nil)
    }

    static func push(repository: BockMediaRepository, memberIdOverride: String? = nil) async throws {
        let prefs = repository.preferences
        let clientId = ClientIdStore.clientId()
        let memberId = memberIdOverride?.nilIfBlank ?? ActiveProfileStore.activeMemberId()
        _ = try await repository.putClientPrefs(
            clientId: clientId,
            memberId: memberId,
            memberPrefs: collectMemberPrefs(prefs: prefs, memberId: memberId),
            clientPrefs: [:]
        )
    }

    // MARK: - Private

    /// Pull household from server, reconcile stale profiles, restore phone/binding picks.
    @discardableResult
    private static func syncHouseholdProfile(
        repository: BockMediaRepository,
        clientId: String,
        profileSwitch: Bool
    ) async -> Bool {
        let household = await HouseholdStore.refresh(
            repository: repository,
            force: profileSwitch || HouseholdStore.members().isEmpty
        )
        let beforeMember = ActiveProfileStore.activeMemberId()
        await rebindFromPhone(repository: repository, clientId: clientId, household: household)
        var changed = reconcileActiveMember(household)
        if restoreActiveMember(household, clientId: clientId) {
            changed = true
        }
        if ActiveProfileStore.activeMemberId() != beforeMember {
            changed = true
        }
        if changed {
            profileChangeRevision += 1
            NotificationCenter.default.post(name: ClientPrefsSyncNotifications.profileChanged, object: nil)
        }
        return changed
    }

    private static func reconcileActiveMember(_ household: HouseholdResponse) -> Bool {
        guard let mid = ActiveProfileStore.activeMemberId(), !HouseholdStore.memberExists(mid) else { return false }
        ActiveProfileStore.clearStaleMember()
        return true
    }

    private static func restoreActiveMember(_ household: HouseholdResponse, clientId: String) -> Bool {
        guard ActiveProfileStore.activeMemberId() == nil else { return false }
        let deviceId = clientDeviceId(clientId)
        if let fromBinding = household.clientBindings.first(where: { $0.clientDeviceId == deviceId })?.memberId,
           HouseholdStore.memberExists(fromBinding) {
            ActiveProfileStore.setActiveMember(fromBinding)
            return true
        }
        if !ActiveProfileStore.hasProfileChoice(),
           household.members.count == 1,
           let only = household.members.first?.id {
            ActiveProfileStore.setActiveMember(only)
            return true
        }
        return false
    }

    private static func clientDeviceId(_ clientId: String) -> String {
        let cid = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cid.isEmpty else { return "" }
        return "client-\(cid)"
    }

    private static func rebindFromPhone(
        repository: BockMediaRepository,
        clientId: String,
        household: HouseholdResponse
    ) async {
        if ActiveProfileStore.activeMemberId() != nil { return }
        let phoneId = InstallIdentity.phoneId()
        guard !phoneId.isEmpty else { return }
        let model = UIDevice.current.model.trimmingCharacters(in: .whitespacesAndNewlines)
        let label = model.isEmpty ? "This phone" : "iOS · \(model)"
        guard let memberId = try? await repository.connectInstall(
            phoneId: phoneId,
            deviceName: label,
            clientId: clientId
        ), household.members.contains(where: { $0.id == memberId }) else { return }
        ActiveProfileStore.setActiveMember(memberId)
    }

    private static func collectMemberPrefs(prefs: AppPreferences, memberId: String?) -> [String: Any] {
        var out: [String: Any] = [
            "searchAllLibraries": prefs.searchAllLibraries,
            "downloadWifiOnly": prefs.downloadWifiOnly,
            "crossfadeSeconds": prefs.crossfadeSeconds,
            "continueAfterQueue": prefs.continueAfterQueue,
            "nowPlayingVideo": prefs.nowPlayingVideo,
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
        if let pins = HomeSectionPinsStore.exportJson() {
            out["homeSectionPins"] = pins
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
            let library = LibraryPrefsStore.load(from: prefs)
            out["libraryTab"] = LibraryPrefsStore.tabValue(for: library.filter)
            out["libraryViewMode"] = library.viewMode == .grid ? "grid" : "list"
            out["librarySortBy"] = library.sort == .recents ? "recents" : "name"
            out["librarySortOrder"] = library.sort == .recents ? "desc" : "asc"
        }
        return out
    }

    static func applyMerged(
        prefs: AppPreferences,
        merged: [String: Any],
        repository: BockMediaRepository? = nil,
        profileSwitch: Bool = false
    ) {
        if merged.isEmpty, !profileSwitch { return }
        if let v = merged["searchAllLibraries"] as? Bool {
            prefs.searchAllLibraries = v
        } else if profileSwitch {
            prefs.searchAllLibraries = true
        }
        if let path = merged["searchSourcePath"] as? String, !path.isEmpty {
            prefs.searchSourcePath = path
        } else if profileSwitch || merged.keys.contains("searchSourcePath") {
            prefs.searchSourcePath = nil
        }
        if let v = merged["downloadWifiOnly"] as? Bool {
            prefs.downloadWifiOnly = v
        } else if profileSwitch {
            prefs.downloadWifiOnly = false
        }
        if let v = merged["crossfadeSeconds"].flatMap(intPref) {
            prefs.crossfadeSeconds = min(20, max(0, v))
        } else if profileSwitch {
            prefs.crossfadeSeconds = 0
        }
        if let v = merged["continueAfterQueue"] as? String {
            prefs.continueAfterQueue = v
        } else if profileSwitch {
            prefs.continueAfterQueue = "off"
        }
        if let v = merged["nowPlayingVideo"] as? Bool {
            prefs.nowPlayingVideo = v
        } else if profileSwitch {
            prefs.nowPlayingVideo = false
        }
        if let v = merged["rememberMe"] as? Bool {
            prefs.rememberMe = v
        }
        // Do not restore activeMemberId from server prefs — profile is chosen explicitly on-device.
        if let items = decodeSelections(merged["searchSelections"]) {
            SearchHistoryStore.replaceSelections(items)
        }
        if let raw = merged["homeTileEngagement"] as? String {
            HomeTileEngagement.importJson(raw)
        }
        if let raw = merged["homeSectionPins"] as? String {
            HomeSectionPinsStore.importJson(raw)
            HomeFeedCache.invalidate()
            HomeLoadCoordinator.resetReloadWindow()
        }
        if let last = merged["lastDevice"] as? String, !last.isEmpty {
            prefs.lastDevice = last
        }
        if let pinned = decodeStringList(merged["pinnedDevices"]) {
            PinnedDevicesStore.setPinned(pinned)
        }
        if let repository {
            if let records = OfflineDownloadSync.decode(merged["offlineDownloads"]) {
                OfflineDownloadSync.applyRemote(records, repository: repository)
            } else if profileSwitch {
                OfflineDownloadSync.applyRemote([], repository: repository)
            }
            OfflineDownloadManager.shared.refresh()
        }
        if merged.keys.contains(where: { ["libraryTab", "libraryViewMode", "librarySortBy"].contains($0) }) {
            LibraryPrefsStore.applyRemote(
                tab: merged["libraryTab"] as? String,
                viewMode: merged["libraryViewMode"] as? String,
                sortBy: merged["librarySortBy"] as? String,
                sortOrder: merged["librarySortOrder"] as? String,
                prefs: prefs
            )
        }
        prefs.notifyProfilePrefsApplied()
        NotificationCenter.default.post(name: ClientPrefsSyncNotifications.prefsApplied, object: nil)
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

    private static func intPref(_ value: Any) -> Int? {
        if let v = value as? Int { return v }
        if let v = value as? NSNumber { return v.intValue }
        if let v = value as? Double { return Int(v.rounded()) }
        if let s = value as? String, let v = Int(s.trimmingCharacters(in: .whitespaces)) { return v }
        return nil
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
