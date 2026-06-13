import SwiftUI

struct SleepTimerSheet: View {
    let deviceName: String
    let hasSleep: Bool
    let onSetMinutes: (Int) async -> Void
    let onSetSongs: (Int) async -> Void
    let onCancel: () async -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Text("Playback stops at the end of the current song.")
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                ForEach([15, 30, 45, 60], id: \.self) { min in
                    Button("\(min) minutes") {
                        Task {
                            await onSetMinutes(min)
                            onDismiss()
                        }
                    }
                }
                Button("After this song") {
                    Task {
                        await onSetSongs(1)
                        onDismiss()
                    }
                }
                Button("After 3 songs") {
                    Task {
                        await onSetSongs(3)
                        onDismiss()
                    }
                }
                if hasSleep {
                    Button("Cancel timer", role: .destructive) {
                        Task {
                            await onCancel()
                            onDismiss()
                        }
                    }
                }
            }
            .navigationTitle("Sleep — \(deviceName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onDismiss) }
            }
        }
        .presentationDetents([.medium])
    }
}

struct UpNextSheet: View {
    let repository: BockMediaRepository
    let tracks: [UpcomingTrack]
    let isLocal: Bool
    let onPlayAtIndex: (Int) -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                if tracks.isEmpty {
                    ContentUnavailableView {
                        Label("Nothing queued", icon: .musicNote, size: 40)
                    }
                } else {
                    List(Array(tracks.enumerated()), id: \.offset) { idx, track in
                        Button {
                            onPlayAtIndex(idx)
                        } label: {
                            UpNextTrackRow(repository: repository, track: track, showPlay: isLocal)
                        }
                        .disabled(!isLocal)
                    }
                }
            }
            .navigationTitle("Up next")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onDismiss) }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct UpNextTrackRow: View {
    let repository: BockMediaRepository
    let track: UpcomingTrack
    var showPlay = false
    @State private var artURL: URL?

    var body: some View {
        HStack(spacing: 12) {
            BockArtwork(url: artURL, size: 48, cornerRadius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title ?? "Track")
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(1)
                Text(track.artist ?? "")
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if showPlay {
                BockIcon(icon: .playArrow, size: 20)
                    .foregroundStyle(BockColors.muted)
            }
        }
        .task(id: track.path) {
            guard let path = track.path,
                  let urlStr = await repository.artworkURL(for: path),
                  let url = URL(string: urlStr) else {
                artURL = nil
                return
            }
            artURL = url
        }
    }
}

struct StreamHistorySheet: View {
    @ObservedObject var appState: AppState
    let onDismiss: () -> Void

    @State private var items: [StreamHistoryItem] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView().tint(BockColors.green)
                } else if items.isEmpty {
                    ContentUnavailableView {
                        Label("No history", icon: .history, size: 40)
                    }
                } else {
                    List(Array(items.enumerated()), id: \.offset) { _, item in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.track ?? "Track").foregroundStyle(BockColors.onSurface)
                            Text([item.artist, item.device].compactMap { $0 }.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                        }
                    }
                }
            }
            .navigationTitle("Recently played")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onDismiss) }
            }
            .task {
                loading = true
                defer { loading = false }
                items = (try? await appState.repository.streamHistory(limit: 50))?.items ?? []
            }
        }
        .presentationDetents([.medium, .large])
    }
}
