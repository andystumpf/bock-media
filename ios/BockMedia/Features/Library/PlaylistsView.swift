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
    @State private var showMixMuse = false
    @State private var mixMuseSeed: DiscoverySeed?
    @State private var showAcquire = false
    @State private var acquireSeed: DiscoverySeed?
    @State private var editMode: EditMode = .inactive
    private let pageSize = 100

    private var canReorder: Bool {
        filter.isEmpty && sortBy == "title" && sortOrder == "asc"
    }

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
                List {
                    Section {
                        playlistHeader
                        HStack(spacing: 8) {
                            Image(systemName: "magnifyingglass")
                                .foregroundStyle(BockColors.muted)
                            TextField("Search in playlist", text: $filter)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }
                        filterSortBar
                    }
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)

                    Section {
                        ForEach(indexedTracks, id: \.key) { item in
                            trackRow(index: item.index + 1, track: item.track)
                        }
                        .onMove(perform: canReorder && editMode == .active ? moveTracks : nil)
                        if loadingMore {
                            HStack { Spacer(); ProgressView(); Spacer() }
                        } else if tracks.count < total {
                            Color.clear.frame(height: 1)
                                .onAppear { Task { await loadMoreIfNeeded() } }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .environment(\.editMode, $editMode)
            }
        }
        .navigationTitle(name.isEmpty ? "Playlist" : name)
        .searchable(text: $filter, prompt: "Search in playlist")
        .toolbar {
            if canReorder {
                ToolbarItem(placement: .topBarLeading) {
                    EditButton()
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Sort by title") { Task { await applySort(by: "title", order: sortOrder) } }
                    Button("Sort by artist") { Task { await applySort(by: "artist", order: sortOrder) } }
                    Button("Sort by album") { Task { await applySort(by: "album", order: sortOrder) } }
                    Button("Ascending") { Task { await applySort(by: sortBy, order: "asc") } }
                    Button("Descending") { Task { await applySort(by: sortBy, order: "desc") } }
                    Divider()
                    Button {
                        mixMuseSeed = DiscoverySeed(kind: .playlist, title: name, playlistId: playlistId)
                        showMixMuse = true
                    } label: { Label("Mix Muse playlist…", systemImage: "sparkles") }
                    Button { Task { await runResonanceRadio(seed: playlistSeed) } } label: {
                        Label("Resonance radio", systemImage: "waveform")
                    }
                    Button { Task { await runResonanceMix(seed: playlistSeed) } } label: {
                        Label("Resonance mix (save)", systemImage: "music.note.list")
                    }
                    Button {
                        acquireSeed = playlistSeed
                        showAcquire = true
                    } label: {
                        Label("Music to seek out…", systemImage: "binoculars")
                    }
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
            editMode = .inactive
            Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                await reload()
            }
        }
        .onChange(of: editMode) { _, mode in
            if mode == .active {
                Task { await loadAllTracksIfNeeded() }
            }
        }
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: mixMuseSeed)
        }
        .sheet(isPresented: $showAcquire) {
            AcquireIdeasSheet(appState: appState, seed: acquireSeed)
        }
    }

    private var playlistSeed: DiscoverySeed {
        DiscoverySeed(kind: .playlist, title: name, playlistId: playlistId)
    }

    private func runResonanceRadio(seed: DiscoverySeed) async {
        do {
            let resp = try await appState.repository.resonanceRadio(
                seedKind: seed.kind.rawValue,
                path: seed.path, album: seed.album, artist: seed.artist,
                playlistId: seed.playlistId
            )
            await appState.repository.playDiscoveryTracksLocally(resp.tracks, title: resp.name ?? seed.title, shuffle: true)
            appState.showNowPlayingSheet = true
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func runResonanceMix(seed: DiscoverySeed) async {
        do {
            let resp = try await appState.repository.resonanceMix(
                seedKind: seed.kind.rawValue,
                path: seed.path, album: seed.album, artist: seed.artist,
                playlistId: seed.playlistId, save: true
            )
            if let pid = resp.playlistId ?? resp.id, !pid.isEmpty {
                appState.pendingPlayTarget = .playlist(id: pid, name: resp.name ?? "Resonance mix")
            }
        } catch {
            appState.toast = error.localizedDescription
        }
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
                sortChip("Title", sortBy == "title") { Task { await applySort(by: "title", order: sortOrder) } }
                sortChip("Artist", sortBy == "artist") { Task { await applySort(by: "artist", order: sortOrder) } }
                sortChip("Album", sortBy == "album") { Task { await applySort(by: "album", order: sortOrder) } }
                sortChip("↑", sortOrder == "asc") { Task { await applySort(by: sortBy, order: "asc") } }
                sortChip("↓", sortOrder == "desc") { Task { await applySort(by: sortBy, order: "desc") } }
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 8)
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
                DiscoveryContextMenuItems(
                    appState: appState,
                    seed: DiscoverySeed(kind: .song, title: track.title ?? path, path: path, album: track.album, artist: track.artist),
                    showMixMuse: $showMixMuse,
                    mixMuseSeed: $mixMuseSeed,
                    showAcquire: $showAcquire,
                    acquireSeed: $acquireSeed
                )
                Divider()
                Button(role: .destructive) {
                    Task {
                        try? await appState.repository.removePlaylistTrack(playlistId: playlistId, path: path)
                        await reload()
                    }
                } label: { Text("Remove") }
            }
        }
    }

    private func applySort(by: String, order: String) async {
        editMode = .inactive
        sortBy = by
        sortOrder = order
        await reload()
        if filter.isEmpty {
            try? await appState.repository.sortPlaylist(id: playlistId, sortBy: by, order: order)
        }
    }

    private func moveTracks(from source: IndexSet, to destination: Int) {
        var reordered = tracks
        reordered.move(fromOffsets: source, toOffset: destination)
        tracks = reordered
        guard let moved = reordered.indices.contains(destination) ? reordered[destination].path : nil else { return }
        Task {
            try? await appState.repository.movePlaylistTrack(
                playlistId: playlistId, path: moved, toIndex: destination
            )
            await reload()
        }
    }

    private func loadAllTracksIfNeeded() async {
        guard tracks.count < total else { return }
        var page = 1
        var all: [PlaylistTrack] = []
        while true {
            guard let detail = try? await appState.repository.playlistDetail(
                id: playlistId, page: page, limit: pageSize, sortBy: sortBy, order: sortOrder
            ) else { break }
            if page == 1 { name = detail.name; total = detail.total > 0 ? detail.total : detail.tracks.count }
            all.append(contentsOf: detail.tracks)
            if detail.tracks.count < pageSize || all.count >= total { break }
            page += 1
        }
        tracks = all
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
