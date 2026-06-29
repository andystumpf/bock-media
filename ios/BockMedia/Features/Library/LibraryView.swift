import SwiftUI

struct LibraryView: View {
    @ObservedObject var appState: AppState
    @State private var libraryData: LibraryData?
    @State private var searchItems: [LibraryItem]?
    @State private var loading = true
    @State private var refreshing = false
    @State private var filter: LibraryFilter = .all
    @State private var viewMode: LibraryViewMode = .list
    @State private var sort: LibrarySort = .recents
    @State private var search = ""
    @State private var prefsLoaded = false

    private var displayItems: [LibraryItem] {
        if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return searchItems ?? []
        }
        return libraryData?.forFilter(filter) ?? []
    }

    private var sortedItems: [LibraryItem] {
        switch sort {
        case .name:
            return displayItems.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        case .recents:
            return displayItems.sorted {
                if $0.sortDate != $1.sortDate { return $0.sortDate > $1.sortDate }
                return $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            libraryHeader
            searchField
            filterRow
            if filter == .all || filter == .playlists {
                NavigationLink(value: LibraryRoute.managePlaylists) {
                    Text("Manage playlists")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 4)
                }
                .buttonStyle(.plain)
            }
            content
        }
        .task {
            let prefs = LibraryPrefsStore.load(from: appState.preferences)
            filter = prefs.filter
            viewMode = prefs.viewMode
            sort = prefs.sort
            prefsLoaded = true
            await bootstrapLibrary()
        }
        .onChange(of: filter) { _, newValue in
            guard prefsLoaded else { return }
            LibraryPrefsStore.save(filter: newValue, viewMode: viewMode, sort: sort, prefs: appState.preferences)
            if let libraryData { prefetchArt(libraryData.forFilter(newValue)) }
        }
        .onChange(of: viewMode) { _, _ in
            guard prefsLoaded else { return }
            LibraryPrefsStore.save(filter: filter, viewMode: viewMode, sort: sort, prefs: appState.preferences)
        }
        .onChange(of: sort) { _, _ in
            guard prefsLoaded else { return }
            LibraryPrefsStore.save(filter: filter, viewMode: viewMode, sort: sort, prefs: appState.preferences)
        }
        .onChange(of: search) { _, newValue in
            Task { await runSearch(newValue) }
        }
        .refreshable {
            refreshing = true
            LibrarySessionCache.invalidate()
            await refreshFromNetwork()
        }
    }

    @ViewBuilder
    private var content: some View {
        if loading && displayItems.isEmpty {
            LoadingBox().frame(maxHeight: .infinity)
        } else if sortedItems.isEmpty {
            Text(emptyMessage)
                .foregroundStyle(BockColors.muted)
                .multilineTextAlignment(.center)
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if viewMode == .grid {
            ScrollView {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                    ForEach(sortedItems) { item in
                        LibraryGridTile(appState: appState, item: item, route: libraryRoute(for: item))
                    }
                }
                .padding(16)
            }
        } else {
            List(sortedItems) { item in
                if let route = libraryRoute(for: item) {
                    NavigationLink(value: route) {
                        LibraryItemRowContent(appState: appState, item: item)
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.35))
                } else {
                    LibraryItemRow(appState: appState, item: item) {
                        appState.play(item.playTarget)
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.35))
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .accessibilityIdentifier(BockTestTags.libraryList)
        }
    }

    private var emptyMessage: String {
        if filter == .downloaded && search.isBlank {
            return "Nothing downloaded yet — tap download on Home tiles or long-press for more."
        }
        if !search.isBlank {
            return "No results for \"\(search)\"."
        }
        return "No items found in your library."
    }

    private var libraryHeader: some View {
        HStack {
            Text("Your Library")
                .font(.title3.bold())
            Spacer()
            NavigationLink(value: LibraryRoute.favorites) {
                BockIcon(icon: .star, size: 22)
                    .foregroundStyle(BockColors.green)
            }
            Menu {
                Button("Recents") { sort = .recents }
                Button("Alphabetical") { sort = .name }
            } label: {
                Text(sort == .recents ? "Recents" : "A–Z")
                    .font(.subheadline.weight(.semibold))
            }
            Button {
                viewMode = viewMode == .list ? .grid : .list
            } label: {
                BockIcon(icon: viewMode == .list ? .gridView : .list, size: 22)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private var searchField: some View {
        HStack {
            BockIcon(icon: .search, size: 18)
                .foregroundStyle(BockColors.muted)
            TextField("Search in Your Library", text: $search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        .padding(10)
        .background(BockColors.surfaceVariant.opacity(0.5))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .padding(.horizontal, 16)
    }

    private var filterRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(LibraryFilter.allCases) { f in
                    Button { filter = f } label: {
                        Text(f.label)
                            .font(.subheadline.weight(filter == f ? .bold : .medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(filter == f ? BockColors.pillActive : BockColors.pillInactive)
                            .foregroundStyle(filter == f ? BockColors.onPrimary : BockColors.onSurface)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    private func bootstrapLibrary() async {
        if let cached = LibrarySessionCache.peek() {
            libraryData = cached
            loading = false
            prefetchArt(cached.forFilter(filter))
        } else if let disk = LibraryCachePersistence.load() {
            libraryData = disk
            LibrarySessionCache.put(disk)
            loading = false
            prefetchArt(disk.forFilter(filter))
        }
        if LibrarySessionCache.getIfFresh() == nil {
            if libraryData == nil { loading = true }
            await refreshFromNetwork()
        }
    }

    private func refreshFromNetwork() async {
        let fresh = await LibraryLoader.loadBuckets(repository: appState.repository)
        libraryData = fresh
        LibrarySessionCache.put(fresh)
        LibraryCachePersistence.save(fresh)
        prefetchArt(fresh.forFilter(filter))
        loading = false
        refreshing = false
    }

    private func runSearch(_ query: String) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty {
            searchItems = nil
            return
        }
        try? await Task.sleep(nanoseconds: 300_000_000)
        guard search.trimmingCharacters(in: .whitespacesAndNewlines) == q else { return }
        searchItems = await LibraryLoader.search(repository: appState.repository, filter: filter, query: q)
        prefetchArt(searchItems ?? [])
    }

    private func prefetchArt(_ items: [LibraryItem]) {
        Task {
            for item in items.prefix(24) {
                _ = await appState.repository.resolveLibraryArtUrl(for: item)
            }
        }
    }

    private func libraryRoute(for item: LibraryItem) -> LibraryRoute? {
        switch item.kind {
        case .playlist:
            return item.playlistId.map { .playlistDetail($0) }
        case .artist:
            return item.artistName.map { .albums(artist: $0) }
        case .album:
            return .songs(artist: item.artistName, album: item.albumName)
        case .downloaded:
            return nil
        }
    }
}

private struct LibraryItemRowContent: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem

    var body: some View {
        HStack(spacing: 12) {
            LibraryItemArt(appState: appState, item: item, size: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(1)
                Text(item.subtitle)
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                    .lineLimit(1)
            }
            Spacer()
            PlayDownloadActions(appState: appState, target: item.playTarget, compact: true)
        }
    }
}

private struct LibraryItemRow: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            LibraryItemRowContent(appState: appState, item: item)
        }
        .buttonStyle(.plain)
    }
}

private struct LibraryGridTile: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem
    let route: LibraryRoute?

    var body: some View {
        Group {
            if let route {
                NavigationLink(value: route) { tileBody }
            } else {
                Button { appState.play(item.playTarget) } label: { tileBody }
                    .buttonStyle(.plain)
            }
        }
    }

    private var tileBody: some View {
        VStack(alignment: .leading, spacing: 8) {
            LibraryItemArt(appState: appState, item: item, size: 160)
            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
                .foregroundStyle(BockColors.onSurface)
            Text(item.subtitle)
                .font(.caption)
                .foregroundStyle(BockColors.muted)
                .lineLimit(1)
            PlayDownloadActions(appState: appState, target: item.playTarget, compact: true)
        }
    }
}

private struct LibraryItemArt: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem
    let size: CGFloat
    @State private var url: URL?

    var body: some View {
        ArtworkWithUnplayedBadge(showUnplayed: item.kind == .album && item.unplayed) {
            BockArtwork(url: url, size: size, cornerRadius: item.kind == .artist ? size / 2 : 6)
        }
        .task(id: item.id) {
            url = await appState.repository.resolveLibraryArtUrl(for: item)
        }
    }
}

private extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
