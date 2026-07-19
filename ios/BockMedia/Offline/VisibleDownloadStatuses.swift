import SwiftUI

private struct VisibleDownloadStatusesKey: EnvironmentKey {
    static let defaultValue: [String: OfflineCollectionStatus] = [:]
}

extension EnvironmentValues {
    var visibleDownloadStatuses: [String: OfflineCollectionStatus] {
        get { self[VisibleDownloadStatusesKey.self] }
        set { self[VisibleDownloadStatusesKey.self] = newValue }
    }
}

enum VisibleDownloadStatuses {
    static func snapshot(from all: [String: OfflineCollectionStatus]) -> [String: OfflineCollectionStatus] {
        let ids = OfflineDownloadSync.visibleCollectionIds()
        return all.filter { ids.contains($0.key) }
    }
}

struct VisibleDownloadStatusesProvider<Content: View>: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var manager = OfflineDownloadManager.shared
    @ViewBuilder var content: () -> Content

    var body: some View {
        content()
            .environment(\.visibleDownloadStatuses, VisibleDownloadStatuses.snapshot(from: manager.statuses))
            .onChange(of: appState.profileChangeRevision) { _, _ in
                manager.refresh()
            }
    }
}
