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
    @State private var page = 1
    @State private var total = 0
    @State private var loading = true
    @State private var loadingMore = false
    private let pageSize = 25

    var body: some View {
        NavigationStack {
            Group {
                if loading && items.isEmpty {
                    ProgressView().tint(BockColors.green)
                } else if items.isEmpty {
                    ContentUnavailableView {
                        Label("No history", icon: .history, size: 40)
                    }
                } else {
                    List {
                        ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.track ?? "Track").foregroundStyle(BockColors.onSurface)
                                Text([item.artist, item.device].compactMap { $0 }.joined(separator: " · "))
                                    .font(.caption)
                                    .foregroundStyle(BockColors.muted)
                            }
                        }
                        if loadingMore {
                            HStack { Spacer(); ProgressView(); Spacer() }
                        } else if items.count < total {
                            Color.clear.frame(height: 1)
                                .onAppear { Task { await loadMore() } }
                        }
                    }
                }
            }
            .navigationTitle("Recently played")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onDismiss) }
            }
            .task { await reload() }
        }
        .presentationDetents([.medium, .large])
    }

    private func reload() async {
        page = 1
        loading = true
        defer { loading = false }
        if let response = try? await appState.repository.streamHistory(page: 1, limit: pageSize) {
            items = response.items
            total = response.total
        } else {
            items = []
            total = 0
        }
    }

    private func loadMore() async {
        guard !loadingMore, items.count < total else { return }
        loadingMore = true
        defer { loadingMore = false }
        let next = page + 1
        if let response = try? await appState.repository.streamHistory(page: next, limit: pageSize) {
            items.append(contentsOf: response.items)
            page = next
            total = response.total
        }
    }
}

struct AddToRoomSheet: View {
    let repository: BockMediaRepository
    let path: String
    let track: String
    let artist: String?
    let remoteOk: Bool
    let onDismiss: () -> Void
    let onDone: (String) -> Void

    @State private var options: [DeviceOption] = []
    @State private var loading = true
    @State private var submitting = false

    var body: some View {
        NavigationStack {
            Group {
                if !remoteOk {
                    Text("Connect Alexa to queue songs on speakers.")
                        .foregroundStyle(BockColors.muted)
                        .padding()
                } else if loading {
                    ProgressView().padding()
                } else if options.isEmpty {
                    Text("No speakers found.").foregroundStyle(BockColors.muted).padding()
                } else {
                    List(options) { opt in
                        Button {
                            submit(deviceId: opt.value, label: opt.label)
                        } label: {
                            Text(opt.label)
                        }
                        .disabled(submitting)
                    }
                }
            }
            .navigationTitle("Add to room")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
            }
            .task { await loadDevices() }
        }
    }

    private func loadDevices() async {
        if let snapshot = DeviceCatalog.peek(), DeviceCatalog.isFresh() {
            options = snapshot.options.filter { !$0.value.hasPrefix("group:") }
            loading = false
            return
        }
        let snapshot = await DeviceCatalog.refresh(repository: repository, probe: false)
        options = snapshot.options.filter { !$0.value.hasPrefix("group:") }
        loading = false
    }

    private func submit(deviceId: String, label: String) {
        submitting = true
        Task {
            do {
                let item = try await repository.roomRequest(
                    deviceId: deviceId,
                    path: path,
                    track: track,
                    artist: artist
                )
                let msg = item.status == "queued"
                    ? "Request sent — waiting for approval"
                    : "Added to \(label.trimmingCharacters(in: .whitespaces))"
                onDone(msg)
            } catch {
                onDone(error.localizedDescription)
                submitting = false
            }
        }
    }
}

struct RoomRequestsSheet: View {
    @ObservedObject var appState: AppState
    let deviceId: String
    let deviceName: String?
    let requests: [RoomRequestItem]
    let onUpdated: () async -> [RoomRequestItem]
    let onDismiss: () -> Void

    @State private var items: [RoomRequestItem] = []
    @State private var busy = false
    @State private var isParent = false

    var body: some View {
        NavigationStack {
            List {
                if items.isEmpty {
                    Text("No household requests for this room.")
                        .foregroundStyle(BockColors.muted)
                }
                ForEach(items) { req in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(req.track ?? "Track")
                            let who = req.byMemberName ?? "Someone"
                            let pending = req.status == "queued" ? " · awaiting approval" : ""
                            Text("\(who)\(pending)").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                        if isParent, req.status == "queued" {
                            Button {
                                Task { await approve(req) }
                            } label: {
                                Image(systemName: "checkmark.circle")
                            }
                            .disabled(busy)
                        }
                        Button(role: .destructive) {
                            Task { await remove(req) }
                        } label: {
                            Image(systemName: "trash")
                        }
                        .disabled(busy)
                    }
                }
            }
            .navigationTitle(deviceName ?? "Room queue")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close", action: onDismiss) }
            }
            .onAppear { items = requests }
            .task {
                if let h = try? await appState.repository.household() {
                    let me = ActiveProfileStore.activeMemberId()
                    isParent = h.members.contains { $0.id == me && $0.isParent }
                }
            }
        }
    }

    private func approve(_ req: RoomRequestItem) async {
        let me = ActiveProfileStore.activeMemberId() ?? ""
        guard let pin = ParentPinCache.get(memberId: me), !pin.isEmpty else {
            appState.toast = "Set a parent PIN first"
            return
        }
        busy = true
        defer { busy = false }
        _ = try? await appState.repository.approveRoomRequest(deviceId: deviceId, requestId: req.id, pin: pin)
        items = await onUpdated()
    }

    private func remove(_ req: RoomRequestItem) async {
        busy = true
        defer { busy = false }
        try? await appState.repository.deleteRoomRequest(deviceId: deviceId, requestId: req.id)
        items = await onUpdated()
    }
}
