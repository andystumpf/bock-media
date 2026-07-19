import SwiftUI

struct LibraryView: View {
    @ObservedObject var appState: AppState
    @State private var libraryData: LibraryData?
    @State private var searchItems: [LibraryItem]?
    @State private var trackBrowse: [LibraryItem] = []
    @State private var trackBrowseLoading = false
    @State private var loading = true
    @State private var refreshing = false
    @State private var filter: LibraryFilter = .all
    @State private var viewMode: LibraryViewMode = .list
    @State private var sort: LibrarySort = .recents
    @State private var search = ""
    @State private var prefsLoaded = false
    @State private var unifiedResults: SearchResponse?
    @State private var unifiedLoading = false
    @State private var paginatedItems: [LibraryItem] = []
    @State private var paginatedPage = 0
    @State private var paginatedTotal = 0
    @State private var paginatedLoading = false
    @State private var paginatedLoadingMore = false
    @State private var resultFilter: SearchView.SearchResultFilter = .all
    @State private var expandedSections: Set<String> = []
    @State private var expandedResults: [String: SearchResponse] = [:]
    @State private var libraryHealth: LibraryHealthResponse?
    @State private var libraryArtEpoch = 0

    private var usesPagination: Bool {
        filter == .artists || filter == .albums || filter == .tracks
    }

    private var unifiedSearchActive: Bool {
        filter == .all && !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var displayItems: [LibraryItem] {
        if unifiedSearchActive { return [] }
        if filter == .tracks {
            if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return usesPagination ? paginatedItems : (searchItems ?? [])
            }
            return trackBrowse
        }
        if usesPagination {
            return paginatedItems
        }
        if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return searchItems ?? []
        }
        return libraryData?.forFilter(filter) ?? []
    }

    private var sortedItems: [LibraryItem] {
        if filter == .playlists {
            switch sort {
            case .name:
                return displayItems.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
            case .recents:
                return displayItems
            }
        }
        switch sort {
        case .name:
            return displayItems.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        case .recents:
            return displayItems.enumerated().sorted { a, b in
                if a.element.sortDate != b.element.sortDate { return a.element.sortDate > b.element.sortDate }
                return a.offset < b.offset
            }.map(\.element)
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
            if search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let libraryHealth {
                LibraryHealthBanner(health: libraryHealth, appState: appState) {
                    Task { await loadLibraryHealth() }
                }
            }
            content
        }
        .task {
            let prefs = LibraryPrefsStore.load(from: appState.preferences)
            filter = prefs.filter
            viewMode = prefs.viewMode
            sort = prefs.sort
            prefsLoaded = true
            TabWarmCoordinator.warmLibrary(repository: appState.repository)
            await bootstrapLibrary()
            if filter == .tracks {
                await loadTrackBrowse()
            }
        }
        .onChange(of: filter) { _, newValue in
            guard prefsLoaded else { return }
            LibraryPrefsStore.save(filter: newValue, viewMode: viewMode, sort: sort, prefs: appState.preferences)
            if newValue == .tracks {
                Task { await loadTrackBrowse() }
            } else if let libraryData {
                prefetchArt(libraryData.forFilter(newValue))
            }
            Task { await runSearch(search) }
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
        .onChange(of: appState.profileChangeRevision) { _, _ in
            let prefs = LibraryPrefsStore.load(from: appState.preferences)
            filter = prefs.filter
            viewMode = prefs.viewMode
            sort = prefs.sort
            LibrarySessionCache.invalidate()
            Task { await refreshFromNetwork() }
        }
        .refreshable {
            refreshing = true
            LibrarySessionCache.invalidate()
            await refreshFromNetwork()
        }
    }

    @ViewBuilder
    private var content: some View {
        if unifiedSearchActive {
            if unifiedLoading && unifiedResults == nil {
                LoadingBox().frame(maxHeight: .infinity)
            } else if let unifiedResults, unifiedResults.hasAnyMatches {
                VStack(spacing: 0) {
                    SearchResultFilterChips(selected: $resultFilter)
                    SearchResultsView(
                        appState: appState,
                        results: unifiedResults,
                        query: search,
                        resultFilter: $resultFilter,
                        expandedSections: $expandedSections,
                        expandedResults: $expandedResults
                    )
                }
            } else {
                Text("No results for \"\(search)\".")
                    .foregroundStyle(BockColors.muted)
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else if loading && displayItems.isEmpty && !(filter == .tracks && trackBrowseLoading) {
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
                        LibraryGridTile(appState: appState, item: item, route: libraryRoute(for: item), artEpoch: libraryArtEpoch)
                    }
                }
                .padding(16)
            }
        } else {
            List(sortedItems) { item in
                if let route = libraryRoute(for: item) {
                    NavigationLink(value: route) {
                        LibraryItemRowContent(appState: appState, item: item, artEpoch: libraryArtEpoch)
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.35))
                } else {
                    LibraryItemRow(appState: appState, item: item, artEpoch: libraryArtEpoch) {
                        appState.play(item.playTarget)
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.35))
                }
            }
            .onAppear {
                if usesPagination, paginatedItems.count < paginatedTotal {
                    Task { await loadMorePaginated() }
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
        if let cached = LibrarySessionCache.getIfFresh() {
            libraryData = cached
            loading = false
            prefetchArt(cached.forFilter(filter))
        } else if let disk = LibraryCachePersistence.load() {
            libraryData = disk
            loading = false
            prefetchArt(disk.forFilter(filter))
        } else {
            loading = true
        }
        await refreshFromNetwork()
    }

    private func refreshFromNetwork() async {
        let fresh = await LibraryLoader.loadBuckets(repository: appState.repository)
        libraryData = fresh
        LibrarySessionCache.put(fresh)
        LibraryCachePersistence.save(fresh)
        prefetchArt(fresh.forFilter(filter))
        loading = false
        refreshing = false
        await loadLibraryHealth()
    }

    private func loadLibraryHealth() async {
        libraryHealth = try? await appState.repository.libraryHealth()
    }

    private func loadPaginatedBrowse(reset: Bool) async {
        guard usesPagination else { return }
        if reset {
            paginatedLoading = true
            paginatedPage = 0
            paginatedItems = []
        }
        defer { paginatedLoading = false }
        let q = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let nextPage = reset ? 1 : paginatedPage + 1
        let page = await LibraryLoader.loadPage(
            repository: appState.repository,
            filter: filter,
            search: q,
            page: nextPage,
            limit: 60
        )
        if reset {
            paginatedItems = page.items
        } else {
            paginatedItems.append(contentsOf: page.items)
        }
        paginatedPage = nextPage
        paginatedTotal = page.total
        prefetchArt(page.items)
    }

    private func runSearch(_ query: String) async {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty {
            searchItems = nil
            unifiedResults = nil
            if usesPagination {
                await loadPaginatedBrowse(reset: true)
            } else {
                paginatedItems = []
                paginatedPage = 0
                paginatedTotal = 0
            }
            return
        }
        try? await Task.sleep(nanoseconds: UnifiedSearchCoordinator.debounceMs)
        guard search.trimmingCharacters(in: .whitespacesAndNewlines) == q else { return }

        if filter == .all {
            unifiedLoading = true
            defer { unifiedLoading = false }
            if let cached = UnifiedSearchCoordinator.resolveCached(q), cached.fromCache {
                unifiedResults = cached.response
                return
            }
            if let cached = UnifiedSearchCoordinator.resolveCached(q), let prefix = cached.prefixExtension {
                unifiedResults = prefix
            }
            let fetched = try? await LibraryLoader.unifiedSearch(
                repository: appState.repository,
                query: q,
                source: appState.preferences.effectiveSearchSource()
            )
            guard search.trimmingCharacters(in: .whitespacesAndNewlines) == q else { return }
            if let fetched {
                unifiedResults = fetched
                SearchQueryCache.put(q, fetched)
            }
            return
        }

        unifiedResults = nil
        if usesPagination {
            paginatedLoading = true
            defer { paginatedLoading = false }
            let page = await LibraryLoader.loadPage(
                repository: appState.repository,
                filter: filter,
                search: q,
                page: 1,
                limit: 60
            )
            guard search.trimmingCharacters(in: .whitespacesAndNewlines) == q else { return }
            paginatedItems = page.items
            paginatedPage = 1
            paginatedTotal = page.total
            prefetchArt(page.items)
            return
        }

        searchItems = await LibraryLoader.search(repository: appState.repository, filter: filter, query: q)
        prefetchArt(searchItems ?? [])
    }

    private func loadMorePaginated() async {
        guard usesPagination, !paginatedLoadingMore else { return }
        guard paginatedItems.count < paginatedTotal else { return }
        paginatedLoadingMore = true
        defer { paginatedLoadingMore = false }
        let q = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let nextPage = paginatedPage + 1
        let page = await LibraryLoader.loadPage(
            repository: appState.repository,
            filter: filter,
            search: q,
            page: nextPage,
            limit: 60
        )
        paginatedItems.append(contentsOf: page.items)
        paginatedPage = nextPage
        paginatedTotal = page.total
        prefetchArt(page.items)
    }

    private func prefetchArt(_ items: [LibraryItem]) {
        guard !items.isEmpty else { return }
        Task {
            let playlistIds = items.compactMap(\.playlistId)
            if !playlistIds.isEmpty {
                await appState.repository.prefetchPlaylistCoverPaths(ids: playlistIds)
            }
            for item in items.prefix(24) {
                _ = await appState.repository.resolveLibraryArtUrl(for: item)
            }
            await MainActor.run { libraryArtEpoch += 1 }
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
        case .track, .downloaded:
            return nil
        }
    }

    private func loadTrackBrowse() async {
        trackBrowseLoading = true
        defer { trackBrowseLoading = false }
        trackBrowse = await LibraryLoader.loadTracks(
            repository: appState.repository,
            search: "",
            limit: 100
        )
        prefetchArt(trackBrowse)
    }
}

private struct LibraryItemRowContent: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem
    var artEpoch: Int = 0

    var body: some View {
        HStack(spacing: 12) {
            LibraryItemArt(appState: appState, item: item, size: 56, artEpoch: artEpoch)
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
    var artEpoch: Int = 0
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            LibraryItemRowContent(appState: appState, item: item, artEpoch: artEpoch)
        }
        .buttonStyle(.plain)
    }
}

private struct LibraryGridTile: View {
    @ObservedObject var appState: AppState
    let item: LibraryItem
    let route: LibraryRoute?
    var artEpoch: Int = 0

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
            LibraryItemArt(appState: appState, item: item, size: 160, artEpoch: artEpoch)
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
    var artEpoch: Int = 0
    @State private var url: URL?

    var body: some View {
        ArtworkWithUnplayedBadge(showUnplayed: item.kind == .album && item.unplayed) {
            BockArtwork(url: url, size: size, cornerRadius: item.kind == .artist ? size / 2 : 6)
        }
        .task(id: "\(item.id)-\(artEpoch)") {
            url = await appState.repository.resolveLibraryArtUrl(for: item)
        }
    }
}

private extension String {
    var isBlank: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
