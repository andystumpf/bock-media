import SwiftUI

struct PlaylistsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [PlaylistSummary] = []
    @State private var loading = true
    @State private var artEpoch = 0

    var body: some View {
        Group {
            if loading {
                LoadingBox()
            } else if items.isEmpty {
                Text("No playlists for this profile.")
                    .foregroundStyle(BockColors.muted)
                    .multilineTextAlignment(.center)
                    .padding(24)
            } else {
                List(items) { pl in
                    NavigationLink(value: LibraryRoute.playlistDetail(pl.id)) {
                        HStack(spacing: 12) {
                            PlaylistRowArt(
                                appState: appState,
                                playlistId: pl.id,
                                title: pl.name,
                                artPath: pl.artPath,
                                size: 56,
                                artEpoch: artEpoch
                            )
                            VStack(alignment: .leading) {
                                Text(pl.name).foregroundStyle(BockColors.onSurface)
                                Text(playlistShareSubtitle(pl))
                                    .font(.caption)
                                    .foregroundStyle(BockColors.muted)
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
        .accessibilityIdentifier(BockTestTags.playlistsListBody)
        .navigationTitle("Playlists")
        .task { await load() }
        .refreshable { await load(forceRefresh: true) }
        .onChange(of: appState.profileChangeRevision) { _, _ in
            Task { await load(forceRefresh: true) }
        }
    }

    private func load(forceRefresh: Bool = false) async {
        let memberKey = ActiveProfileStore.activeMemberId() ?? ""
        if forceRefresh {
            SessionDataStore.invalidatePlaylists()
        } else if let cached = SessionDataStore.peekPlaylists(memberKey: memberKey) {
            items = cached.items
            loading = false
        } else if items.isEmpty {
            loading = true
        }
        defer { loading = false }
        items = (try? await appState.repository.playlists(limit: 300, memberScoped: true))?.items ?? items
        await appState.repository.prefetchPlaylistCoverPaths(ids: items.map(\.id))
        artEpoch += 1
    }
}

private func playlistShareSubtitle(_ pl: PlaylistSummary) -> String {
    let base = "\(pl.tracks) tracks"
    guard let badge = playlistShareBadge(
        ownerMemberId: pl.ownerMemberId,
        ownerName: pl.ownerName,
        visibility: pl.visibility,
        sharedWith: pl.sharedWith,
        daily: pl.daily,
        activeMemberId: ActiveProfileStore.activeMemberId(),
        memberName: { _ in nil }
    ) else { return base }
    return "\(base) · \(badge)"
}

private func playlistShareBadge(
    ownerMemberId: String?,
    ownerName: String?,
    visibility: String?,
    sharedWith: [String],
    daily: Bool,
    activeMemberId: String?,
    memberName: (String) -> String?
) -> String? {
    if daily { return "Daily" }
    let vis = (visibility ?? "household").lowercased()
    let me = activeMemberId?.trimmingCharacters(in: .whitespacesAndNewlines)
    let meId = (me?.isEmpty == false) ? me : nil
    if vis == "shared", let owner = ownerMemberId?.trimmingCharacters(in: .whitespacesAndNewlines), !owner.isEmpty, owner != meId,
       let name = ownerName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty {
        return "From \(name)"
    }
    if vis == "private", ownerMemberId == meId { return "Private" }
    if vis == "shared", ownerMemberId == meId, !sharedWith.isEmpty {
        let names = sharedWith.compactMap { memberName($0) }
        if !names.isEmpty { return "Shared · \(names.joined(separator: ", "))" }
        return "Shared"
    }
    return nil
}

struct SharePlaylistSheet: View {
    let members: [HouseholdMember]
    let alreadyShared: Set<String>
    let onShare: ([String]) -> Void
    let onCancel: () -> Void
    @State private var selected: Set<String>

    init(
        members: [HouseholdMember],
        alreadyShared: Set<String>,
        onShare: @escaping ([String]) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.members = members
        self.alreadyShared = alreadyShared
        self.onShare = onShare
        self.onCancel = onCancel
        _selected = State(initialValue: alreadyShared)
    }

    var body: some View {
        NavigationStack {
            Group {
                if members.isEmpty {
                    Text("Add household members in Family to share playlists.")
                        .foregroundStyle(BockColors.muted)
                        .padding()
                } else {
                    List(members) { member in
                        Button {
                            if selected.contains(member.id) {
                                selected.remove(member.id)
                            } else {
                                selected.insert(member.id)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(member.name)
                                    Text(member.role.capitalized)
                                        .font(.caption)
                                        .foregroundStyle(BockColors.muted)
                                }
                                Spacer()
                                if selected.contains(member.id) {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(BockColors.pillActive)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Share with…")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Share") { onShare(Array(selected)) }
                        .disabled(selected.isEmpty)
                }
            }
        }
    }
}

struct PlaylistDetailView: View {
    @ObservedObject var appState: AppState
    let playlistId: String
    var suggestHomePin: Bool = false
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var tracks: [PlaylistTrack] = []
    @State private var total = 0
    @State private var filter = ""
    @State private var sortBy = "original"
    @State private var sortOrder = "asc"
    @State private var loading = true
    @State private var loadingMore = false
    @State private var loadError: String?
    @State private var showMixMuse = false
    @State private var mixMuseSeed: DiscoverySeed?
    @State private var showAcquire = false
    @State private var acquireSeed: DiscoverySeed?
    @State private var reorderMode = false
    @State private var heroArtURL: URL?
    @State private var showPlaylistMenu = false
    @State private var trackMenuTrack: PlaylistTrack?
    @State private var isDaily = false
    @State private var ownerMemberId: String?
    @State private var ownerName: String?
    @State private var visibility: String?
    @State private var sharedWith: [String] = []
    @State private var showShare = false
    @State private var showHomePin = false
    @State private var homePinPrompted = false
    @State private var householdMembers: [HouseholdMember] = []
    @State private var addToRoom: AddToRoomContext?
    private let pageSize = 100

    private var canReorder: Bool {
        filter.isEmpty && sortBy == "original"
    }

    /// Stable, unique identity for each row. `key` = "offset-trackId": the trackId keeps
    /// identity tied to content (so sort/filter/pagination reuse views correctly) and the
    /// offset prefix keeps keys unique when duplicate paths share the same trackId.
    private var indexedTracks: [(key: String, index: Int, track: PlaylistTrack)] {
        tracks.enumerated().map { (key: "\($0.offset)-\($0.element.id)", index: $0.offset, track: $0.element) }
    }

    var body: some View {
        ZStack {
            ArtBackdrop(url: heroArtURL)
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: name.isEmpty ? "Playlist" : name)
                Group {
                    if loading && tracks.isEmpty {
                        LoadingBox()
                            .frame(maxHeight: .infinity)
                    } else if let loadError, tracks.isEmpty {
                        VStack(spacing: 12) {
                            Text(loadError).foregroundStyle(.red)
                            Button("Retry") { Task { await loadPage(1, append: false) } }
                        }
                        .padding()
                        .frame(maxHeight: .infinity)
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                PlexampPlaylistHero(
                                    appState: appState,
                                    playlistId: playlistId,
                                    name: name.isEmpty ? "Playlist" : name,
                                    tracks: tracks,
                                    artURL: heroArtURL,
                                    onPlay: { appState.play(.playlist(id: playlistId, name: name)) },
                                    onShuffle: {
                                        Task {
                                            await LocalPlaybackController.shared.playTarget(
                                                repository: appState.repository,
                                                target: .playlist(id: playlistId, name: name),
                                                shuffle: true
                                            )
                                            if LocalPlaybackController.shared.state.error == nil {
                                                appState.showNowPlayingSheet = true
                                            }
                                        }
                                    },
                                    onMenu: { showPlaylistMenu = true }
                                )
                                playlistMetaSection
                                playlistSearchRow
                                filterSortBar
                                if reorderMode && !canReorder {
                                    Text("Clear search and reset sort to reorder tracks.")
                                        .font(.caption)
                                        .foregroundStyle(.white.opacity(0.55))
                                        .padding(.horizontal, 16)
                                        .padding(.bottom, 8)
                                }
                                ForEach(indexedTracks, id: \.key) { item in
                                    HStack(spacing: 0) {
                                        if reorderMode && canReorder {
                                            reorderControls(for: item.index)
                                        }
                                        PlexampPlaylistTrackRow(
                                            appState: appState,
                                            track: item.track,
                                            onTap: { playTrackAtIndex(item.index, track: item.track) },
                                            onMenu: { trackMenuTrack = item.track }
                                        )
                                    }
                                }
                                if loadingMore {
                                    HStack { Spacer(); BockProgressIndicator(size: 32); Spacer() }
                                        .padding(.vertical, 12)
                                } else if tracks.count < total {
                                    Color.clear.frame(height: 1)
                                        .onAppear { Task { await loadMoreIfNeeded() } }
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.bottom, 24)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .accessibilityIdentifier(BockTestTags.playlistDetailBody)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .confirmationDialog(name.isEmpty ? "Playlist" : name, isPresented: $showPlaylistMenu, titleVisibility: .visible) {
            if canReorder {
                Button(reorderMode ? "Done reordering" : "Reorder tracks") {
                    reorderMode.toggle()
                    if reorderMode { Task { await loadAllTracksIfNeeded() } }
                }
            }
            Button("Sort by title") { Task { await applySort(by: "title") } }
            Button("Sort by artist") { Task { await applySort(by: "artist") } }
            Button("Sort by album") { Task { await applySort(by: "album") } }
            Button("Ascending") { Task { await applySort(order: "asc") } }
            Button("Descending") { Task { await applySort(order: "desc") } }
            Button("Mix Muse playlist…") {
                mixMuseSeed = DiscoverySeed(kind: .playlist, title: name, playlistId: playlistId)
                showMixMuse = true
            }
            Button("Resonance radio") { Task { await runResonanceRadio(seed: playlistSeed) } }
            Button("Resonance mix (save)") { Task { await runResonanceMix(seed: playlistSeed) } }
            Button("Music to seek out…") {
                acquireSeed = playlistSeed
                showAcquire = true
            }
            Button("Add to Home row…") {
                showHomePin = true
            }
            if !isDaily {
                Button("Share with…") {
                    Task {
                        householdMembers = (try? await appState.repository.household().members) ?? []
                        showShare = true
                    }
                }
            }
            Button("Delete playlist", role: .destructive) {
                Task {
                    try? await appState.repository.deletePlaylist(id: playlistId)
                    dismiss()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(trackMenuTrack?.title ?? "Track", isPresented: Binding(
            get: { trackMenuTrack != nil },
            set: { if !$0 { trackMenuTrack = nil } }
        ), titleVisibility: .visible) {
            if let track = trackMenuTrack, let path = track.path {
                Button("Play") { playTrackAtIndex(tracks.firstIndex(where: { $0.path == path }) ?? 0, track: track) }
                if appState.remoteOk {
                    Button("Add to room") {
                        addToRoom = AddToRoomContext(path: path, title: track.title ?? path, artist: track.artist)
                    }
                }
                Button("Remove from playlist", role: .destructive) {
                    Task {
                        try? await appState.repository.removePlaylistTrack(playlistId: playlistId, path: path)
                        await reload()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .onChange(of: filter) { _, _ in
            reorderMode = false
            Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                await reload()
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
        .sheet(isPresented: $showHomePin) {
            HomeSectionPinSheet(
                playlistId: playlistId,
                playlistName: name.isEmpty ? "Playlist" : name,
                suggestedSectionId: HomePinTargets.suggestSectionId(playlistName: name),
                onDismiss: { showHomePin = false },
                onPinned: { sectionTitle in
                    appState.toast = "Pinned to \(sectionTitle) on Home"
                }
            )
        }
        .onChange(of: name) { _, newName in
            guard suggestHomePin || appState.suggestHomePinPlaylistId == playlistId else { return }
            guard !homePinPrompted, !loading, !newName.isEmpty else { return }
            homePinPrompted = true
            showHomePin = true
            if appState.suggestHomePinPlaylistId == playlistId {
                appState.suggestHomePinPlaylistId = nil
            }
        }
        .sheet(isPresented: $showShare) {
            let me = ActiveProfileStore.activeMemberId()
            SharePlaylistSheet(
                members: householdMembers.filter { !$0.id.isEmpty && $0.id != me },
                alreadyShared: Set(sharedWith),
                onShare: { ids in
                    Task {
                        do {
                            try await appState.repository.sharePlaylist(id: playlistId, toMemberIds: ids)
                            sharedWith = ids.sorted()
                            visibility = "shared"
                            showShare = false
                            appState.toast = "Playlist shared"
                        } catch {
                            appState.toast = error.localizedDescription
                        }
                    }
                },
                onCancel: { showShare = false }
            )
        }
        .sheet(item: $addToRoom) { ctx in
            AddToRoomSheet(
                repository: appState.repository,
                path: ctx.path,
                track: ctx.title,
                artist: ctx.artist,
                remoteOk: appState.remoteOk,
                onDismiss: { addToRoom = nil },
                onDone: { msg in
                    appState.toast = msg
                    addToRoom = nil
                }
            )
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

    private var playlistMetaSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("\(total > 0 ? total : tracks.count) songs")
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.65))
                Spacer()
                if !name.isEmpty {
                    PlayDownloadActions(
                        appState: appState,
                        target: .playlist(id: playlistId, name: name),
                        compact: true
                    )
                }
            }
            if let badge = playlistShareBadge(
                ownerMemberId: ownerMemberId,
                ownerName: ownerName,
                visibility: visibility,
                sharedWith: sharedWith,
                daily: isDaily,
                activeMemberId: ActiveProfileStore.activeMemberId(),
                memberName: { id in householdMembers.first(where: { $0.id == id })?.name }
            ) {
                Text(badge)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BockColors.pillActive)
            }
            if isDaily {
                Text("Fresh daily mix — these songs change every day. Save it to keep today's set.")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
                Button {
                    Task { await saveDaily() }
                } label: {
                    Label("Save to your library", systemImage: "bookmark")
                        .font(.subheadline.weight(.bold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(BockColors.pillActive)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var playlistSearchRow: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.white.opacity(0.45))
            TextField("Search in playlist", text: $filter)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func saveDaily() async {
        do {
            try await appState.repository.saveDailyPlaylist(id: playlistId, name: nil)
            isDaily = false
            appState.toast = "Saved to your library"
            await reload()
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private var filterSortBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                sortChip("Title", sortBy == "title") { Task { await applySort(by: "title") } }
                sortChip("Artist", sortBy == "artist") { Task { await applySort(by: "artist") } }
                sortChip("Album", sortBy == "album") { Task { await applySort(by: "album") } }
                sortChip("↑", sortOrder == "asc" && sortBy != "original") { Task { await applySort(order: "asc") } }
                sortChip("↓", sortOrder == "desc" && sortBy != "original") { Task { await applySort(order: "desc") } }
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

    private func reorderControls(for index: Int) -> some View {
        VStack(spacing: 2) {
            Button {
                moveTrack(from: index, to: index - 1)
            } label: {
                Image(systemName: "chevron.up")
                    .font(.caption.weight(.bold))
            }
            .disabled(index <= 0)
            Button {
                moveTrack(from: index, to: index + 1)
            } label: {
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.bold))
            }
            .disabled(index >= tracks.count - 1)
        }
        .foregroundStyle(.white.opacity(0.65))
        .frame(width: 28)
        .padding(.leading, 8)
    }

    private func moveTrack(from index: Int, to newIndex: Int) {
        guard newIndex >= 0, newIndex < tracks.count, index != newIndex else { return }
        var reordered = tracks
        let item = reordered.remove(at: index)
        reordered.insert(item, at: newIndex)
        tracks = reordered
        guard let path = item.path else { return }
        Task {
            try? await appState.repository.movePlaylistTrack(
                playlistId: playlistId, path: path, toIndex: newIndex
            )
            await reload()
        }
    }

    private func playTrackAtIndex(_ index: Int, track: PlaylistTrack) {
        Task {
            var slots: [LocalTrack?] = Array(repeating: nil, count: tracks.count)
            await withTaskGroup(of: (Int, LocalTrack?).self) { group in
                for (i, t) in tracks.enumerated() {
                    group.addTask {
                        guard let path = t.path, !path.isEmpty,
                              let urlStr = await appState.repository.streamURL(for: path),
                              let url = URL(string: urlStr) else { return (i, nil) }
                        return (i, LocalTrack(
                            path: path,
                            title: t.title ?? path,
                            artist: t.artist,
                            album: t.album,
                            streamURL: url
                        ))
                    }
                }
                for await (i, resolved) in group {
                    slots[i] = resolved
                }
            }
            let localTracks = slots.compactMap { $0 }
            guard !localTracks.isEmpty else { return }
            let startIdx = localTracks.firstIndex(where: { $0.path == track.path }) ?? min(index, localTracks.count - 1)
            LocalPlaybackController.shared.setPlayContext(
                repository: appState.repository,
                target: .playlist(id: playlistId, name: name)
            )
            try? await LocalPlaybackController.shared.playTracks(localTracks, shuffle: false, startIndex: startIdx)
            if LocalPlaybackController.shared.state.error == nil {
                appState.showNowPlayingSheet = true
            }
        }
    }

    private func applySort(by: String? = nil, order: String? = nil) async {
        reorderMode = false
        if let by {
            sortBy = (by == sortBy) ? "original" : by
        }
        if let order, sortBy != "original" {
            sortOrder = order
        }
        await reload()
        if filter.isEmpty, sortBy != "original" {
            try? await appState.repository.sortPlaylist(id: playlistId, sortBy: sortBy, order: sortOrder)
        }
    }

    private func loadHeroArt() async {
        if let cached = try? await appState.repository.playlistCoverPath(id: playlistId),
           let str = await appState.repository.artworkURL(for: cached),
           let url = URL(string: str) {
            heroArtURL = url
            return
        }
        if let path = tracks.compactMap(\.path).first,
           let str = await appState.repository.artworkURL(for: path),
           let url = URL(string: str) {
            heroArtURL = url
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

    private func seedFromSessionCache() {
        let memberKey = ActiveProfileStore.activeMemberId() ?? ""
        guard let pl = SessionDataStore.peekPlaylists(memberKey: memberKey)?.items.first(where: { $0.id == playlistId }) else {
            return
        }
        if name.isEmpty { name = pl.name }
        if total == 0 { total = pl.tracks }
        loading = false
        if heroArtURL == nil, let artPath = pl.artPath ?? HomeArtworkCache.playlistPath(id: playlistId) {
            Task {
                if let str = await appState.repository.artworkURL(for: artPath),
                   let url = URL(string: str) {
                    heroArtURL = url
                }
            }
        }
    }

    private func reload() async {
        if filter.isEmpty { seedFromSessionCache() }
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
            isDaily = detail.daily
            ownerMemberId = detail.ownerMemberId
            ownerName = detail.ownerName
            visibility = detail.visibility
            sharedWith = detail.sharedWith
            if append {
                let seen = Set(tracks.compactMap(\.path))
                tracks.append(contentsOf: detail.tracks.filter { track in
                    guard let path = track.path else { return true }
                    return !seen.contains(path)
                })
            } else {
                tracks = detail.tracks
            }
            await loadHeroArt()
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

private struct AddToRoomContext: Identifiable {
    let path: String
    let title: String
    let artist: String?
    var id: String { path }
}
