import SwiftUI

struct AddToPlaylistContext: Identifiable {
    let path: String
    let title: String
    var id: String { path }
}

struct AddToPlaylistSheet: View {
    @ObservedObject var appState: AppState
    let trackPath: String
    let trackTitle: String
    let onDismiss: () -> Void
    let onAdded: (String) -> Void

    @State private var playlists: [PlaylistSummary] = []
    @State private var loading = true
    @State private var showNew = false
    @State private var newName = ""
    @State private var saving = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(trackTitle)
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
                if showNew {
                    Section("New playlist") {
                        TextField("Playlist name", text: $newName)
                        Button("Create & add") {
                            Task { await createAndAdd() }
                        }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || saving)
                    }
                } else {
                    Button {
                        showNew = true
                    } label: {
                        Label("Create new playlist", icon: .add)
                    }
                }
                Section("Playlists") {
                    if loading {
                        BockProgressIndicator(size: 32)
                    } else if playlists.isEmpty {
                        Text("No playlists").foregroundStyle(BockColors.muted)
                    } else {
                        ForEach(playlists) { pl in
                            Button {
                                Task { await add(to: pl) }
                            } label: {
                                HStack {
                                    Text(pl.name)
                                    Spacer()
                                    Text("\(pl.tracks)").foregroundStyle(BockColors.muted)
                                }
                            }
                            .disabled(saving)
                        }
                    }
                }
            }
            .navigationTitle("Add to playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
            }
            .task { await load() }
        }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        playlists = (try? await appState.repository.playlists(limit: 300))?.items ?? []
    }

    private func add(to playlist: PlaylistSummary) async {
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.addPlaylistTrack(playlistId: playlist.id, path: trackPath)
            onAdded("Added to \"\(playlist.name)\"")
            onDismiss()
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func createAndAdd() async {
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        saving = true
        defer { saving = false }
        do {
            try await appState.repository.createPlaylist(name: name, tracks: [trackPath])
            onAdded("Added to \"\(name)\"")
            onDismiss()
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}
