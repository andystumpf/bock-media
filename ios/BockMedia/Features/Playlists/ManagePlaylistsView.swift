import SwiftUI

struct ManagePlaylistsView: View {
    @ObservedObject var appState: AppState
    @State private var playlists: [PlaylistSummary] = []
    @State private var smart: [SmartPlaylist] = []
    @State private var search = ""
    @State private var showSmartForm = false
    @State private var editSmart: SmartPlaylist?
    @State private var showCreate = false
    @State private var showMerge = false
    @State private var showAI = false

    var body: some View {
        List {
            Section("Smart playlists") {
                if smart.isEmpty {
                    Text("No smart playlists").foregroundStyle(BockColors.muted)
                }
                ForEach(smart) { sp in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(sp.name).foregroundStyle(BockColors.onSurface)
                            Text(sp.enabled ? "Enabled" : "Disabled")
                                .font(.caption)
                                .foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                        Button("Edit") { editSmart = sp }
                            .font(.caption)
                        Button("Refresh") {
                            Task { try? await appState.repository.refreshSmartPlaylist(id: sp.id); await load() }
                        }
                        .font(.caption)
                    }
                }
                Button("New smart playlist") { showSmartForm = true }
            }
            Section("Playlists") {
                ForEach(playlists) { pl in
                    NavigationLink(value: LibraryRoute.playlistDetail(pl.id)) {
                        HStack {
                            Text(pl.name)
                            Spacer()
                            Text("\(pl.tracks)").foregroundStyle(BockColors.muted)
                        }
                    }
                }
            }
        }
        .searchable(text: $search, prompt: "Search playlists")
        .navigationTitle("Manage playlists")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showCreate = true } label: { Label("New playlist", icon: .add) }
                    Button { showMerge = true } label: { Label("Merge playlists", icon: .merge) }
                    Button { showAI = true } label: { Label("Mix Muse playlist", icon: .psychology) }
                } label: {
                    BockIcon(icon: .add, size: 22)
                }
            }
        }
        .task { await load() }
        .onChange(of: search) { _, _ in Task { await load() } }
        .sheet(isPresented: $showSmartForm) {
            SmartPlaylistFormSheet(appState: appState, onDismiss: { showSmartForm = false }, onSaved: {
                showSmartForm = false
                Task { await load() }
            })
        }
        .sheet(item: $editSmart) { sp in
            SmartPlaylistFormSheet(appState: appState, edit: sp, onDismiss: { editSmart = nil }, onSaved: {
                editSmart = nil
                Task { await load() }
            })
        }
        .sheet(isPresented: $showCreate) {
            CreatePlaylistSheet(appState: appState, onDismiss: { showCreate = false }, onSaved: {
                showCreate = false
                Task { await load() }
            })
        }
        .sheet(isPresented: $showMerge) {
            MergePlaylistsSheet(appState: appState, playlists: playlists, onDismiss: { showMerge = false }, onSaved: {
                showMerge = false
                Task { await load() }
            })
        }
        .sheet(isPresented: $showAI) {
            MixMusePromptSheet(appState: appState, seed: nil)
                .onDisappear { Task { await load() } }
        }
    }

    private func load() async {
        playlists = (try? await appState.repository.playlists(search: search, limit: 200))?.items ?? []
        smart = (try? await appState.repository.smartPlaylists()) ?? []
    }
}

struct SmartPlaylistFormSheet: View {
    @ObservedObject var appState: AppState
    var edit: SmartPlaylist?
    let onDismiss: () -> Void
    let onSaved: () -> Void

    @State private var name = ""
    @State private var genre = ""
    @State private var artist = ""
    @State private var maxTracks = "50"
    @State private var enabled = true
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                TextField("Genre (optional)", text: $genre)
                TextField("Artist (optional)", text: $artist)
                TextField("Max tracks", text: $maxTracks).keyboardType(.numberPad)
                if edit != nil { Toggle("Enabled", isOn: $enabled) }
            }
            .navigationTitle(edit == nil ? "Smart playlist" : "Edit smart playlist")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(name.isEmpty || saving)
                }
            }
            .onAppear {
                if let edit {
                    name = edit.name
                    enabled = edit.enabled
                }
            }
        }
    }

    private func save() async {
        saving = true
        defer { saving = false }
        let max = Int(maxTracks) ?? 50
        do {
            if let edit {
                try await appState.repository.updateSmartPlaylist(
                    id: edit.id, name: name, genre: genre.nilIfBlank,
                    artist: artist.nilIfBlank, maxTracks: max, enabled: enabled
                )
            } else {
                try await appState.repository.createSmartPlaylist(
                    name: name, genre: genre.nilIfBlank, artist: artist.nilIfBlank, maxTracks: max
                )
            }
            onSaved()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespaces)
        return t.isEmpty ? nil : t
    }
}
