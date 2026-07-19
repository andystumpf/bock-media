import SwiftUI

struct PlayDownloadActions: View {
    @ObservedObject var appState: AppState
    let target: PlayTarget
    var compact: Bool = false

    @Environment(\.visibleDownloadStatuses) private var visibleDownloadStatuses
    @ObservedObject private var manager = OfflineDownloadManager.shared

    private var status: OfflineCollectionStatus? {
        let id = target.downloadId()
        if !visibleDownloadStatuses.isEmpty {
            return visibleDownloadStatuses[id]
        }
        return manager.status(for: target)
    }

    var body: some View {
        HStack(spacing: compact ? 4 : 8) {
            Button { appState.play(target) } label: {
                BockIcon(icon: .playArrow, size: compact ? 20 : 24)
                    .foregroundStyle(BockColors.green)
            }
            .buttonStyle(.plain)

            if status?.state == .downloading {
                BockProgressIndicator(size: 20)
                    .scaleEffect(0.8)
            } else if status?.state == .idle {
                Circle()
                    .stroke(BockColors.green.opacity(0.55), lineWidth: 2)
                    .frame(width: 20, height: 20)
            } else {
                Button { handleDownloadTap() } label: {
                    BockIcon(icon: status?.state == .complete ? .downloadDone : .download, size: compact ? 20 : 24)
                        .foregroundStyle(status?.state == .complete ? BockColors.green : BockColors.onSurface)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func handleDownloadTap() {
        if status?.state == .complete {
            manager.deleteCollection(target.downloadId())
        } else {
            manager.download(
                repository: appState.repository,
                preferences: appState.preferences,
                target: target
            )
        }
    }
}
