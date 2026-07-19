import SwiftUI

struct DownloadsView: View {
    @ObservedObject var appState: AppState
    var embeddedInTab: Bool = false
    @ObservedObject private var manager = OfflineDownloadManager.shared
    @Environment(\.dismiss) private var dismiss
    @State private var detail: OfflineCollectionStatus?

    private let store = OfflineDownloadStore()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if embeddedInTab {
                TabScreenHeader(title: "Downloads")
            }
            List {
            Section {
                LabeledContent("Storage used", value: formatOfflineBytes(store.bytesOnDisk()))
            }
            let sorted = manager.statuses.values
                .filter { status in
                    OfflineDownloadSync.visibleCollectionIds().contains(status.manifest.id)
                }
                .sorted {
                if $0.state.sortOrder != $1.state.sortOrder { return $0.state.sortOrder < $1.state.sortOrder }
                return $0.manifest.downloadedAtMs > $1.manifest.downloadedAtMs
            }
            if sorted.isEmpty {
                Text("No downloads yet — tap download on a playlist or album from Home or Search.")
                    .foregroundStyle(BockColors.muted)
            } else {
                ForEach(sorted) { status in
                    Button { detail = status } label: {
                        DownloadRow(status: status)
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            manager.deleteCollection(status.manifest.id)
                        } label: {
                            Label("Delete", icon: .delete)
                        }
                    }
                }
            }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .navigationTitle(embeddedInTab ? "" : "Downloads")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !embeddedInTab {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { manager.refresh() }
        .onChange(of: appState.profileChangeRevision) { _, _ in
            OfflineDownloadSync.claimOrphansForActiveProfile()
            manager.refresh()
        }
        .onReceive(NotificationCenter.default.publisher(for: ClientPrefsSyncNotifications.prefsApplied)) { _ in
            OfflineDownloadSync.claimOrphansForActiveProfile()
            manager.refresh()
        }
        .refreshable { manager.refresh() }
        .sheet(item: $detail) { status in
            DownloadDetailView(appState: appState, status: status)
        }
        .accessibilityIdentifier(BockTestTags.downloadsList)
    }
}

private struct DownloadRow: View {
    let status: OfflineCollectionStatus

    var body: some View {
        HStack(spacing: 12) {
            BockIcon(icon: status.state == .complete ? .downloadDone : .download, size: 24)
                .foregroundStyle(status.state == .complete ? BockColors.green : BockColors.muted)
            VStack(alignment: .leading, spacing: 4) {
                Text(status.manifest.title)
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                if status.state == .downloading || status.state == .failed {
                    ProgressView(value: Double(status.progress))
                        .tint(BockColors.green)
                }
            }
        }
    }

    private var subtitle: String {
        let count = status.manifest.tracks.count
        switch status.state {
        case .complete: return "\(count) tracks · \(formatDownloadDate(ms: status.manifest.downloadedAtMs))"
        case .downloading: return "Downloading… \(Int(status.progress * 100))%"
        case .failed: return status.error ?? "Failed"
        case .idle: return "Queued for download"
        }
    }
}

private struct DownloadDetailView: View {
    @ObservedObject var appState: AppState
    let status: OfflineCollectionStatus
    @ObservedObject private var manager = OfflineDownloadManager.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button("Play on this iPhone") {
                        Task {
                            await LocalPlaybackController.shared.playOfflineManifest(status.manifest)
                            dismiss()
                        }
                    }
                    if status.state == .downloading {
                        Button("Cancel download", role: .destructive) {
                            manager.cancelCollection(status.manifest.id)
                            dismiss()
                        }
                    } else if status.state == .failed {
                        Button("Retry") {
                            manager.retry(
                                repository: appState.repository,
                                preferences: appState.preferences,
                                id: status.manifest.id
                            )
                        }
                    } else if status.state == .complete {
                        Button("Resync") {
                            manager.resync(
                                repository: appState.repository,
                                preferences: appState.preferences,
                                target: status.manifest.toPlayTarget()
                            )
                        }
                    }
                }
                Section("Tracks") {
                    ForEach(Array(status.manifest.tracks.enumerated()), id: \.element.id) { idx, track in
                        Button {
                            Task {
                                await LocalPlaybackController.shared.playOfflineManifest(
                                    status.manifest, startIndex: idx
                                )
                                dismiss()
                            }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(track.title).foregroundStyle(BockColors.onSurface)
                                Text(track.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                            }
                        }
                    }
                }
            }
            .navigationTitle(status.manifest.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
    }
}

private extension DownloadState {
    var sortOrder: Int {
        switch self {
        case .downloading: return 0
        case .failed: return 1
        case .complete: return 2
        case .idle: return 3
        }
    }
}
