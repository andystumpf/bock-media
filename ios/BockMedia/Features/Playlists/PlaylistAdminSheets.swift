import SwiftUI

struct CreatePlaylistSheet: View {
    @ObservedObject var appState: AppState
    let onDismiss: () -> Void
    let onSaved: () -> Void

    @State private var name = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Playlist name", text: $name)
            }
            .navigationTitle("New playlist")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { Task { await save() } }.disabled(name.isEmpty || saving)
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.createPlaylist(name: name)
            onSaved()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

struct MergePlaylistsSheet: View {
    @ObservedObject var appState: AppState
    let playlists: [PlaylistSummary]
    let onDismiss: () -> Void
    let onSaved: () -> Void

    @State private var selected = Set<String>()
    @State private var name = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Merged name (optional)", text: $name)
                Section("Select playlists") {
                    ForEach(playlists) { pl in
                        Toggle(pl.name, isOn: Binding(
                            get: { selected.contains(pl.id) },
                            set: { on in
                                if on { selected.insert(pl.id) }
                                else { selected.remove(pl.id) }
                            }
                        ))
                    }
                }
            }
            .navigationTitle("Merge playlists")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Merge") { Task { await merge() } }
                        .disabled(selected.count < 2 || saving)
                }
            }
        }
    }

    private func merge() async {
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.mergePlaylists(ids: Array(selected), name: name.nilIfBlank)
            onSaved()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

struct AiPlaylistSheet: View {
    @ObservedObject var appState: AppState
    let onDismiss: () -> Void
    let onSaved: () -> Void

    @State private var prompt = ""
    @State private var name = "Mix Muse"
    @State private var preview: [PlaylistTrack] = []
    @State private var loading = false
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Describe the playlist", text: $prompt, axis: .vertical)
                    .lineLimit(3...6)
                TextField("Name", text: $name)
                if !preview.isEmpty {
                    Section("Preview (\(preview.count) tracks)") {
                        ForEach(preview.prefix(8)) { track in
                            VStack(alignment: .leading) {
                                Text(track.title ?? "Track")
                                Text(track.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Mix Muse")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Preview") { Task { await runPreview() } }
                        .disabled(prompt.isEmpty || loading)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(prompt.isEmpty || saving)
                }
            }
        }
    }

    private func runPreview() async {
        loading = true
        defer { loading = false }
        do {
            let resp = try await appState.repository.aiPlaylist(
                prompt: prompt, name: name, maxTracks: 30, save: false
            )
            preview = resp.preview
            if let n = resp.name { name = n }
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        do {
            let resp = try await appState.repository.aiPlaylist(
                prompt: prompt, name: name, maxTracks: 30, save: true
            )
            if let id = resp.playlistId ?? resp.id, !id.isEmpty {
                appState.suggestHomePinPlaylistId = id
            }
            onSaved()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
