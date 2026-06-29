import SwiftUI

struct PlayDownloadActions: View {
    @ObservedObject var appState: AppState
    let target: PlayTarget
    var compact: Bool = false

    @ObservedObject private var manager = OfflineDownloadManager.shared

    private var status: OfflineCollectionStatus? { manager.status(for: target) }

    var body: some View {
        HStack(spacing: compact ? 4 : 8) {
            Button { appState.play(target) } label: {
                BockIcon(icon: .playArrow, size: compact ? 20 : 24)
                    .foregroundStyle(BockColors.green)
            }
            .buttonStyle(.plain)

            if status?.state == .downloading {
                ProgressView()
                    .scaleEffect(0.8)
            } else {
                Button { startDownload() } label: {
                    BockIcon(icon: status?.state == .complete ? .downloadDone : .download, size: compact ? 20 : 24)
                        .foregroundStyle(status?.state == .complete ? BockColors.green : BockColors.onSurface)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func startDownload() {
        if status?.state == .complete {
            manager.resync(
                repository: appState.repository,
                preferences: appState.preferences,
                target: target
            )
        } else {
            manager.download(
                repository: appState.repository,
                preferences: appState.preferences,
                target: target
            )
        }
    }
}
