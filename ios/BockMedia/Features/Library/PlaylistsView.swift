import SwiftUI

struct PlaylistsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [PlaylistSummary] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                LoadingBox()
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
    @State private var name = ""
    @State private var tracks: [PlaylistTrack] = []
    @State private var total = 0
    @State private var filter = ""
    @State private var sortBy = "title"
    @State private var sortOrder = "asc"
    @State private var loading = true
    @State private var loadingMore = false
    @State private var loadError: String?
    private let pageSize = 100

    /// Stable, unique identity for each row. `key` = "offset-trackId": the trackId keeps
    /// identity tied to content (so sort/filter/pagination reuse views correctly) and the
    /// offset prefix keeps keys unique when duplicate paths share the same trackId.
    private var indexedTracks: [(key: String, index: Int, track: PlaylistTrack)] {
        tracks.enumerated().map { (key: "\($0.offset)-\($0.element.id)", index: $0.offset, track: $0.element) }
    }

    var body: some View {
        Group {
            if loading && tracks.isEmpty {
                LoadingBox()
            } else if let loadError, tracks.isEmpty {
                VStack(spacing: 12) {
                    Text(loadError).foregroundStyle(.red)
                    Button("Retry") { Task { await loadPage(1, append: false) } }
                }
                .padding()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        playlistHeader
                        filterSortBar
                        // Key combines the track's stable id with its position: the id keeps
                        // SwiftUI identity tied to content across sort/filter/pagination, while
                        // the offset prefix guarantees uniqueness even when a playlist contains
                        // duplicate paths (which share an id and would otherwise crash ForEach).
                        ForEach(indexedTracks, id: \.key) { item in
                            trackRow(index: item.index + 1, track: item.track)
                        }
                        if loadingMore {
                            HStack { Spacer(); ProgressView(); Spacer() }
                                .padding()
                        } else if tracks.count < total {
                            Color.clear.frame(height: 1)
                                .onAppear { Task { await loadMoreIfNeeded() } }
                        }
                    }
                }
            }
        }
        .navigationTitle(name.isEmpty ? "Playlist" : name)
        .searchable(text: $filter, prompt: "Filter tracks")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Sort by title") { sortBy = "title"; Task { await reload() } }
                    Button("Sort by artist") { sortBy = "artist"; Task { await reload() } }
                    Button("Sort by album") { sortBy = "album"; Task { await reload() } }
                    Button("Ascending") { sortOrder = "asc"; Task { await reload() } }
                    Button("Descending") { sortOrder = "desc"; Task { await reload() } }
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
            if !name.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    PlayDownloadActions(
                        appState: appState,
                        target: .playlist(id: playlistId, name: name)
                    )
                }
            }
        }
        .onChange(of: filter) { _, _ in
            Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                await reload()
            }
        }
        .task { await reload() }
        .refreshable { await reload() }
    }

    private var playlistHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(name)
                .font(.title.bold())
            Text("\(total > 0 ? total : tracks.count) songs")
                .font(.subheadline)
                .foregroundStyle(BockColors.muted)
        }
        .padding(16)
    }

    private var filterSortBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                sortChip("Title", sortBy == "title") { sortBy = "title"; Task { await reload() } }
                sortChip("Artist", sortBy == "artist") { sortBy = "artist"; Task { await reload() } }
                sortChip("Album", sortBy == "album") { sortBy = "album"; Task { await reload() } }
                sortChip("↑", sortOrder == "asc") { sortOrder = "asc"; Task { await reload() } }
                sortChip("↓", sortOrder == "desc") { sortOrder = "desc"; Task { await reload() } }
            }
            .padding(.horizontal, 16)
        }
        .padding(.bottom, 8)
    }

    private func sortChip(_ label: String, _ active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.caption.weight(active ? .bold : .regular))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(active ? BockColors.pillActive : BockColors.pillInactive)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func trackRow(index: Int, track: PlaylistTrack) -> some View {
        HStack {
            Text("\(index)")
                .font(.caption)
                .foregroundStyle(BockColors.muted)
                .frame(width: 28, alignment: .trailing)
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
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .contextMenu {
            if let path = track.path {
                Button(role: .destructive) {
                    Task {
                        try? await appState.repository.removePlaylistTrack(playlistId: playlistId, path: path)
                        await reload()
                    }
                } label: { Text("Remove") }
            }
        }
    }

    private func reload() async {
        await loadPage(1, append: false)
    }

    private func loadMoreIfNeeded() async {
        guard !loading, !loadingMore, tracks.count < total else { return }
        let nextPage = (tracks.count / pageSize) + 1
        await loadPage(nextPage, append: true)
    }

    private func loadPage(_ page: Int, append: Bool) async {
        if append {
            guard !loadingMore else { return }
            loadingMore = true
        } else {
            loading = tracks.isEmpty
            loadError = nil
        }
        defer {
            loading = false
            loadingMore = false
        }
        do {
            let detail = try await appState.repository.playlistDetail(
                id: playlistId,
                page: page,
                limit: pageSize,
                q: filter.nilIfBlank,
                sortBy: sortBy,
                order: sortOrder
            )
            name = detail.name
            total = detail.total > 0 ? detail.total : detail.tracks.count
            if append {
                let seen = Set(tracks.compactMap(\.path))
                tracks.append(contentsOf: detail.tracks.filter { track in
                    guard let path = track.path else { return true }
                    return !seen.contains(path)
                })
            } else {
                tracks = detail.tracks
            }
        } catch {
            if !append { loadError = error.localizedDescription }
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
