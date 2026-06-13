import SwiftUI

struct PlaylistsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [PlaylistSummary] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView().tint(BockColors.green)
            } else {
                List(items) { pl in
                    NavigationLink(value: LibraryRoute.playlistDetail(pl.id)) {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(pl.name).foregroundStyle(BockColors.onSurface)
                                Text("\(pl.tracks) tracks").font(.caption).foregroundStyle(BockColors.muted)
                            }
                            Spacer()
                            PlayDownloadActions(
                                appState: appState,
                                target: .playlist(id: pl.id, name: pl.name),
                                compact: true
                            )
                        }
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Playlists")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.playlists(limit: 300))?.items ?? []
    }
}

struct PlaylistDetailView: View {
    @ObservedObject var appState: AppState
    let playlistId: String
    @Environment(\.dismiss) private var dismiss
    @State private var detail: PlaylistDetailResponse?
    @State private var loading = true
    @State private var filter = ""
    @State private var sortBy = "title"
    @State private var sortOrder = "asc"

    var body: some View {
        Group {
            if loading {
                ProgressView()
            } else if let detail {
                List(detail.tracks) { track in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(track.title ?? "Track").foregroundStyle(BockColors.onSurface)
                            Text(track.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                        if let path = track.path {
                            PlayDownloadActions(
                                appState: appState,
                                target: .song(path: path, title: track.title ?? path),
                                compact: true
                            )
                        }
                    }
                    .swipeActions {
                        if let path = track.path {
                            Button(role: .destructive) {
                                Task {
                                    try? await appState.repository.removePlaylistTrack(playlistId: playlistId, path: path)
                                    await load()
                                }
                            } label: { Label("Remove", icon: .remove) }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .searchable(text: $filter, prompt: "Filter tracks")
        .navigationTitle(detail?.name ?? "Playlist")
        .toolbar {
            if let detail {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Sort by title") { sortBy = "title"; Task { await load() } }
                        Button("Sort by artist") { sortBy = "artist"; Task { await load() } }
                        Button("Ascending") { sortOrder = "asc"; Task { await load() } }
                        Button("Descending") { sortOrder = "desc"; Task { await load() } }
                        Divider()
                        Button("Delete playlist", role: .destructive) {
                            Task {
                                try? await appState.repository.deletePlaylist(id: playlistId)
                                dismiss()
                            }
                        }
                    } label: {
                        BockIcon(icon: .moreVert, size: 22)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    PlayDownloadActions(
                        appState: appState,
                        target: .playlist(id: playlistId, name: detail.name)
                    )
                }
            }
        }
        .onChange(of: filter) { _, _ in Task { await load() } }
        .task { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        detail = try? await appState.repository.playlistDetail(
            id: playlistId,
            q: filter.nilIfBlank,
            sortBy: sortBy,
            order: sortOrder
        )
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
