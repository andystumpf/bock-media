import SwiftUI

struct SearchView: View {
    @ObservedObject var appState: AppState
    @FocusState private var searchFocused: Bool

    @State private var query = SearchResultsSessionCache.query
    @State private var results: SearchResponse? = SearchResultsSessionCache.results
    @State private var suggestions: [SearchSuggestion] = SearchResultsSessionCache.suggestions
    @State private var browseFeed: SearchBrowseFeed? = SearchBrowseSessionCache.getIfFresh()
    @State private var browseLoading = true
    @State private var loading = false
    @State private var recentSelections: [SearchRecentSelection] = []
    @State private var showNewReleases = false
    @State private var browseArtworkEpoch = 0
    @State private var searchTask: Task<Void, Never>?
    @State private var searchError: String?
    @State private var searchPins: [SearchPin] = []
    @State private var rankingKind: SearchRankingKind?
    @State private var expandedSections: Set<String> = []
    @State private var expandedResults: [String: SearchResponse] = [:]
    @State private var resultFilter: SearchResultFilter = .all
    @State private var showMixMuse = false
    @State private var showPinEditor = false
    @State private var navigateSonicAdventure = false
    @State private var navigateCreatedPlaylist: String?

    private enum SearchRankingKind: String, CaseIterable {
        case artists = "Top Artists"
        case albums = "Top Albums"
        case tracks = "Top Tracks"
        case bestOf = "Best Of…"
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }
    private var showResults: Bool { !trimmedQuery.isEmpty }
    private var showSuggestions: Bool { false }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabScreenHeader(title: "Search")
            searchField
                .padding(.horizontal, 16)

            if loading && showResults && results == nil {
                LoadingBox(size: 40)
                    .padding()
                    .accessibilityIdentifier(BockTestTags.screenLoading)
            } else if let searchError, showResults {
                Text(searchError)
                    .foregroundStyle(.red)
                    .padding()
                    .accessibilityIdentifier(BockTestTags.searchError)
            } else if showResults, let results {
                if results.hasAnyMatches {
                    SearchResultFilterChips(selected: $resultFilter)
                    SearchResultsView(
                        appState: appState,
                        results: results,
                        query: query,
                        resultFilter: $resultFilter,
                        expandedSections: $expandedSections,
                        expandedResults: $expandedResults
                    )
                } else {
                    searchEmptyState
                }
            } else if showResults {
                searchEmptyState
            } else if showSuggestions {
                suggestionsList
            } else if rankingKind != nil {
                searchRankingView
            } else if showNewReleases {
                newReleasesList
            } else if browseLoading && browseFeed == nil {
                LoadingBox()
                    .padding()
            } else {
                browseScroll
            }
        }
        .task {
            recentSelections = SearchHistoryStore.selections()
            searchPins = (try? await appState.repository.searchPins()) ?? []
            if SearchBrowseSessionCache.getIfFresh() == nil {
                TabWarmCoordinator.warmSearchBrowse(repository: appState.repository)
            }
            await loadBrowseFeed()
        }
        .onAppear {
            applyUITestSearchIfNeeded()
            applyUITestLaunchSearchIfNeeded()
        }
        .onChange(of: appState.uitestSearchNonce) { _, _ in
            applyUITestSearchIfNeeded()
        }
        .onChange(of: appState.uitestSearchQuery) { _, q in
            guard let q, !q.isEmpty else { return }
            applyUITestSearchIfNeeded()
        }
        .onChange(of: appState.profileChangeRevision) { _, _ in
            recentSelections = SearchHistoryStore.selections()
            query = ""
            results = nil
            suggestions = []
            SearchQueryCache.invalidate()
            SearchResultsSessionCache.clear()
            browseFeed = SearchBrowseSessionCache.getIfFresh()
            Task {
                searchPins = (try? await appState.repository.searchPins()) ?? []
                if browseFeed == nil { await loadBrowseFeed() }
            }
        }
        .background {
            NavigationLink(isActive: $navigateSonicAdventure) {
                SearchSonicAdventureView(appState: appState)
            } label: {
                EmptyView()
            }
            .hidden()
            NavigationLink(isActive: Binding(
                get: { navigateCreatedPlaylist != nil },
                set: { if !$0 { navigateCreatedPlaylist = nil } }
            )) {
                if let id = navigateCreatedPlaylist {
                    PlaylistDetailView(
                        appState: appState,
                        playlistId: id,
                        suggestHomePin: true
                    )
                }
            } label: {
                EmptyView()
            }
            .hidden()
        }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: nil, defaultName: "Sonic Sage") { playlistId in
                navigateCreatedPlaylist = playlistId
            }
        }
        .sheet(isPresented: $showPinEditor) {
            SearchPinsEditorSheet(
                appState: appState,
                pins: searchPins,
                onDismiss: { showPinEditor = false },
                onSaved: { searchPins = $0 }
            )
        }
    }

    private var searchField: some View {
        HStack {
            BockIcon(icon: .search, size: 18)
                .foregroundStyle(BockColors.muted)
            TextField("What do you want to listen to?", text: $query)
                .focused($searchFocused)
                .submitLabel(.search)
                .accessibilityIdentifier(BockTestTags.searchField)
                .onSubmit { Task { await runSearch() } }
            if !query.isEmpty {
                Button {
                    query = ""
                    results = nil
                    suggestions = []
                    SearchQueryCache.invalidate()
                    SearchResultsSessionCache.clear()
                } label: {
                    BockIcon(icon: .clear, size: 18)
                        .foregroundStyle(BockColors.muted)
                }
            }
        }
        .padding(10)
        .background(BockColors.surfaceVariant.opacity(0.55))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .onChange(of: query) { _, _ in
            expandedSections = []
            expandedResults = [:]
            searchTask?.cancel()
            searchTask = Task { await handleQueryChange() }
        }
    }

    private var searchEmptyState: some View {
        VStack(spacing: 8) {
            Text("No results")
                .font(.headline)
                .foregroundStyle(BockColors.onSurface)
                .accessibilityIdentifier(BockTestTags.searchEmpty)
            Text("Try another query or pull to refresh your connection.")
                .font(.subheadline)
                .foregroundStyle(BockColors.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(32)
    }

    private var browseScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("Aural fixations")
                        .font(.title3.bold())
                        .foregroundStyle(BockColors.onSurface)
                    Spacer()
                }
                .padding(.horizontal, 16)
                plexampLink("Top Artists") { rankingKind = .artists }
                plexampLink("Top Albums") { rankingKind = .albums }
                plexampLink("Top Tracks") { rankingKind = .tracks }
                plexampLink("Best Of…") { rankingKind = .bestOf }
                ForEach(searchPins, id: \.displayId) { pin in
                    plexampLink(pin.title ?? pin.name ?? "Shortcut") { openSearchPin(pin) }
                }
                Button {
                    showPinEditor = true
                } label: {
                    HStack {
                        Text("Edit shortcuts")
                            .foregroundStyle(BockColors.muted)
                        Spacer()
                        Image(systemName: "pencil")
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.plain)

                Text("Sonic explorations")
                    .font(.title3.bold())
                    .foregroundStyle(BockColors.onSurface)
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
                plexampLink("Sonic Adventure") { openAcquireIdeas() }
                plexampLink("Sonic Sage") { openMixMuse() }

                if !recentSelections.isEmpty {
                    HStack {
                        Text("Recent")
                            .font(.title3.bold())
                            .foregroundStyle(BockColors.onSurface)
                        Spacer()
                        Button("Clear") { SearchHistoryStore.clearSelections(); recentSelections = [] }
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                    }
                    .padding(.horizontal, 16)
                    ForEach(recentSelections, id: \.selectionKey) { item in
                        recentSelectionRow(item)
                    }
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func plexampLink(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .foregroundStyle(BockColors.onSurface)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BockColors.muted)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func recentSelectionRow(_ item: SearchRecentSelection) -> some View {
        switch item.kind {
        case "artist":
            NavigationLink(value: SearchRoute.artist(item.title)) {
                recentSelectionLabel(item)
            }
        case "album":
            NavigationLink(value: SearchRoute.album(name: item.title, artist: item.artist)) {
                recentSelectionLabel(item)
            }
        case "genre":
            NavigationLink(value: SearchRoute.genre(item.title)) {
                recentSelectionLabel(item)
            }
        default:
            Button { openRecentSelection(item) } label: {
                recentSelectionLabel(item)
            }
            .buttonStyle(.plain)
        }
    }

    private func recentSelectionLabel(_ item: SearchRecentSelection) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).foregroundStyle(BockColors.onSurface)
                if let sub = item.subtitle, !sub.isEmpty {
                    Text(sub).font(.caption).foregroundStyle(BockColors.muted)
                }
            }
            Spacer()
            Button {
                SearchHistoryStore.removeSelection(item)
                recentSelections = SearchHistoryStore.selections()
            } label: {
                BockIcon(icon: .close, size: 16).foregroundStyle(BockColors.muted)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func openRecentSelection(_ item: SearchRecentSelection) {
        switch item.kind {
        case "playlist":
            if let id = item.playlistId { appState.play(.playlist(id: id, name: item.title)) }
        case "song":
            if let path = item.path { appState.play(.song(path: path, title: item.title)) }
        default: break
        }
    }

    private func openAcquireIdeas() {
        navigateSonicAdventure = true
    }

    private func openMixMuse() {
        showMixMuse = true
    }

    private func browseAllSection(_ feed: SearchBrowseFeed) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Browse all")
                .font(.title3.bold())
                .foregroundStyle(BockColors.onSurface)
                .padding(.horizontal, 16)

            HStack(spacing: 10) {
                browseTile(
                    title: "New Releases",
                    artPath: feed.newReleases.first?.art_path,
                    fallback: feed.newReleases.first?.name ?? "New",
                    icon: .newReleases
                ) {
                    showNewReleases = true
                }
                if let genre = feed.genres.first {
                    browseGenreTile(genre)
                }
            }
            .padding(.horizontal, 16)

            let rest = Array(feed.genres.dropFirst())
            ForEach(Array(stride(from: 0, to: rest.count, by: 2)), id: \.self) { i in
                HStack(spacing: 10) {
                    browseGenreTile(rest[i])
                    if i + 1 < rest.count {
                        browseGenreTile(rest[i + 1])
                    } else {
                        Color.clear.frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func browseTile(
        title: String,
        artPath: String?,
        fallback: String,
        icon: BockIcons,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                browseArtwork(path: artPath, title: fallback)
                LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
                HStack(spacing: 6) {
                    BockIcon(icon: icon, size: 16)
                    Text(title)
                        .font(.subheadline.weight(.bold))
                }
                .foregroundStyle(.white)
                .padding(10)
            }
            .frame(height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }

    private func browseGenreTile(_ genre: GenreItem) -> some View {
        NavigationLink(value: SearchRoute.genre(genre.name)) {
            ZStack(alignment: .bottomLeading) {
                browseArtwork(path: genre.art_path, title: genre.name)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom)
                Text(genre.name)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(10)
            }
            .frame(height: 96)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .simultaneousGesture(LongPressGesture().onEnded { _ in
            Task { await playGenreRadio(genre) }
        })
    }

    @ViewBuilder
    private func browseArtwork(path: String?, title: String) -> some View {
        if let path {
            BrowseArtwork(path: path, repository: appState.repository, title: title)
        } else {
            Color(BockColors.surfaceVariant)
                .overlay {
                    Text(String(title.prefix(1)).uppercased())
                        .font(.title.bold())
                        .foregroundStyle(BockColors.green)
                }
        }
    }

    private var suggestionsList: some View {
        List(suggestions) { suggestion in
            if let route = suggestionRoute(suggestion) {
                NavigationLink(value: route) {
                    suggestionLabel(suggestion)
                }
                .simultaneousGesture(TapGesture().onEnded {
                    recordSelectionForSuggestion(suggestion)
                })
            } else {
                Button {
                    handleSuggestionTap(suggestion)
                } label: {
                    suggestionLabel(suggestion)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private func suggestionLabel(_ suggestion: SearchSuggestion) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(suggestion.title).foregroundStyle(BockColors.onSurface)
            if let sub = suggestion.subtitle {
                Text(sub).font(.caption).foregroundStyle(BockColors.muted)
            }
        }
    }

    private func suggestionRoute(_ suggestion: SearchSuggestion) -> SearchRoute? {
        guard let target = SearchBrowseLoader.playTarget(for: suggestion) else { return nil }
        switch target {
        case .artist(let name): return .artist(name)
        case .album(let name, let artist): return .album(name: name, artist: artist)
        default: return nil
        }
    }

    private var newReleasesList: some View {
        List {
            Section {
                Button("← Back to browse") { showNewReleases = false }
                    .foregroundStyle(BockColors.green)
            }
            if let albums = browseFeed?.newReleases {
                Section("New releases") {
                    ForEach(albums) { album in
                        NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(album.name).foregroundStyle(BockColors.onSurface)
                                    Text(album.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                                }
                                Spacer()
                                Button {
                                    appState.play(.album(name: album.name, artist: album.artist))
                                } label: {
                                    BockIcon(icon: .playArrow, size: 22).foregroundStyle(BockColors.green)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    private var searchPinsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Aural fixations")
                .font(.title3.bold())
                .foregroundStyle(BockColors.onSurface)
                .padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    ForEach(searchPins, id: \.displayId) { pin in
                        Button { openSearchPin(pin) } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(String((pin.title ?? pin.name ?? "?").prefix(1)).uppercased())
                                    .font(.title2.bold())
                                    .frame(width: 96, height: 96)
                                    .background(BockColors.surfaceVariant)
                                    .clipShape(RoundedRectangle(cornerRadius: 8))
                                Text(pin.title ?? pin.name ?? "Shortcut")
                                    .font(.caption)
                                    .foregroundStyle(BockColors.onSurface)
                                    .lineLimit(2)
                                    .frame(width: 96, alignment: .leading)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private func openSearchPin(_ pin: SearchPin) {
        let title = pin.title ?? pin.name ?? "Shortcut"
        switch pin.kind.lowercased() {
        case "playlist":
            if let id = pin.id { appState.play(.playlist(id: id, name: title)) }
        case "genre":
            appState.play(.radio(displayTitle: "\(title) Radio", seedKind: .genre, name: pin.name ?? title, path: nil))
        case "artist":
            appState.play(.artist(name: pin.name ?? title))
        case "album":
            appState.play(.album(name: pin.name ?? title, artist: pin.artist))
        case "radio":
            appState.play(.radio(displayTitle: title, seedKind: .artist, name: pin.name ?? title, path: pin.path))
        default:
            if let path = pin.path { appState.play(.song(path: path, title: title)) }
        }
    }

    private func handleQueryChange() async {
        try? await Task.sleep(nanoseconds: UnifiedSearchCoordinator.debounceMs)
        guard !Task.isCancelled else { return }
        let q = trimmedQuery
        searchError = nil
        if q.isEmpty {
            results = nil
            suggestions = []
            loading = false
            resultFilter = .all
            expandedSections = []
            expandedResults = [:]
            SearchResultsSessionCache.clear()
            SearchQueryCache.invalidate()
            return
        }
        if UITestSupport.isEnabled {
            SearchQueryCache.invalidate()
            SearchResultsSessionCache.clear()
        } else if let cached = UnifiedSearchCoordinator.resolveCached(q), cached.fromCache {
            results = cached.response
            suggestions = SearchBrowseLoader.suggestionsFromResponse(cached.response)
            loading = false
            SearchResultsSessionCache.saveSnapshot(query: q, results: cached.response, suggestions: suggestions)
            return
        }
        if !UITestSupport.isEnabled, SearchResultsSessionCache.hasFreshResults(q) {
            results = SearchResultsSessionCache.results
            suggestions = SearchResultsSessionCache.suggestions
            loading = false
            return
        }
        if !UITestSupport.isEnabled, let cached = UnifiedSearchCoordinator.resolveCached(q), let prefix = cached.prefixExtension {
            results = prefix
            suggestions = SearchBrowseLoader.suggestionsFromResponse(prefix)
        }
        await runSearch(for: q)
    }

    private func runSearch(for searchFor: String? = nil) async {
        let q = searchFor ?? trimmedQuery
        guard !q.isEmpty else { return }
        if results == nil || searchFor != nil {
            loading = true
        }
        defer { loading = false }
        let source: String? = {
            if UITestSupport.isEnabled { return nil }
            return appState.preferences.effectiveSearchSource()
        }()
        do {
            let full = try await UnifiedSearchCoordinator.fetch(
                repository: appState.repository,
                query: q,
                source: source
            )
            guard trimmedQuery == q else { return }
            results = full
            suggestions = SearchBrowseLoader.suggestionsFromResponse(full)
            SearchQueryCache.put(q, full)
            SearchResultsSessionCache.saveSnapshot(query: q, results: full, suggestions: suggestions)
        } catch {
            guard trimmedQuery == q else { return }
            searchError = UnifiedSearchCoordinator.userErrorMessage(error, remoteOk: appState.remoteOk)
            if results == nil {
                results = SearchResponse()
            }
        }
    }

    private func applyUITestLaunchSearchIfNeeded() {
        guard UITestSupport.isEnabled else { return }
        let args = ProcessInfo.processInfo.arguments
        let q: String? = {
            if let idx = args.firstIndex(of: "-UITestSearchQuery"), idx + 1 < args.count {
                return args[idx + 1].trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return ProcessInfo.processInfo.environment["UITEST_SEARCH_QUERY"]?
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }()
        guard let q, !q.isEmpty else { return }
        query = q
        SearchQueryCache.invalidate()
        SearchResultsSessionCache.clear()
        Task { await runSearch() }
    }

    private func applyUITestSearchIfNeeded() {
        guard let q = appState.uitestSearchQuery, !q.isEmpty else { return }
        query = q
        appState.uitestSearchQuery = nil
        Task { await runSearch() }
    }

    private func handleSuggestionTap(_ suggestion: SearchSuggestion) {
        if let target = SearchBrowseLoader.playTarget(for: suggestion) {
            appState.play(target)
        } else {
            query = suggestion.title
        }
        recordSelectionForSuggestion(suggestion)
    }

    private func recordSelection(_ selection: SearchRecentSelection?) {
        guard let selection else { return }
        SearchHistoryStore.addSelection(selection)
        recentSelections = SearchHistoryStore.selections()
    }

    private func recordSelectionForSuggestion(_ suggestion: SearchSuggestion) {
        let selection: SearchRecentSelection? = switch suggestion.kind {
        case .artist: .fromArtist(suggestion.title)
        case .album: .fromAlbum(suggestion.title, artist: suggestion.artist)
        case .playlist:
            suggestion.hitId.map { .fromPlaylist(id: $0, name: suggestion.title) }
        case .song:
            SearchRecentSelection(kind: "song", title: suggestion.title, subtitle: suggestion.artist, artist: suggestion.artist, path: suggestion.path)
        }
        recordSelection(selection)
    }

    @ViewBuilder
    private var searchRankingView: some View {
        if let kind = rankingKind {
            VStack(spacing: 0) {
                HStack {
                    Button { rankingKind = nil; rankingRowsCache = [:] } label: {
                        BockIcon(icon: .arrowBack, size: 22).foregroundStyle(BockColors.onSurface)
                    }
                    Text(kind.rawValue).font(.headline).foregroundStyle(BockColors.onSurface)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                if kind == .bestOf {
                    rankingYearHeader
                } else {
                    rankingWeekHeader
                }
                if let rows = rankingRows(for: kind), !rows.isEmpty {
                    List {
                        ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                            rankingRowLink(index: index + 1, row: row, kind: kind)
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                } else {
                    LoadingBox().padding()
                }
            }
            .task(id: rankingCacheKey(kind)) { await loadRankingRows(kind: kind) }
        }
    }

    private var rankingCacheKey: (SearchRankingKind) -> String {
        { kind in
            if kind == .bestOf { return "bestof-\(rankingYear)" }
            return "\(kind.rawValue)-\(rankingWeekOffset)"
        }
    }

    private var rankingWeekHeader: some View {
        let range = weekRange(offset: rankingWeekOffset)
        return HStack {
            Button { rankingWeekOffset -= 1; rankingRowsCache = [:] } label: {
                Image(systemName: "chevron.left").foregroundStyle(BockColors.onSurface)
            }
            VStack(spacing: 2) {
                Text("Weekly").font(.headline).foregroundStyle(BockColors.onSurface)
                Text(range.label).font(.caption).foregroundStyle(BockColors.muted)
            }
            Spacer(minLength: 0)
            Button {
                if rankingWeekOffset < 0 { rankingWeekOffset += 1; rankingRowsCache = [:] }
            } label: {
                Image(systemName: "chevron.right")
                    .foregroundStyle(rankingWeekOffset < 0 ? BockColors.onSurface : BockColors.muted.opacity(0.35))
            }
            .disabled(rankingWeekOffset >= 0)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var rankingYearHeader: some View {
        let currentYear = Calendar.current.component(.year, from: Date())
        return HStack {
            Button { rankingYear -= 1; rankingRowsCache = [:] } label: {
                Image(systemName: "chevron.left").foregroundStyle(BockColors.onSurface)
            }
            Text(String(rankingYear)).font(.headline).foregroundStyle(BockColors.onSurface)
            Spacer()
            Button {
                if rankingYear < currentYear { rankingYear += 1; rankingRowsCache = [:] }
            } label: {
                Image(systemName: "chevron.right")
                    .foregroundStyle(rankingYear < currentYear ? BockColors.onSurface : BockColors.muted.opacity(0.35))
            }
            .disabled(rankingYear >= currentYear)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func rankingRowLabel(index: Int, row: RankingRow, kind: SearchRankingKind) -> some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.title3.bold())
                .frame(width: 28, alignment: .leading)
            rankingArtwork(path: row.artPath, title: row.headline, round: kind == .artists)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.headline).font(.body.weight(.semibold)).foregroundStyle(BockColors.onSurface).lineLimit(1)
                if let sub = row.subtitle {
                    Text(sub).font(.subheadline).foregroundStyle(BockColors.muted).lineLimit(1)
                }
                Text(row.playsLabel).font(.caption).foregroundStyle(BockColors.muted)
            }
            Spacer()
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private func rankingArtwork(path: String?, title: String, round: Bool) -> some View {
        let art = browseArtwork(path: path, title: title).frame(width: 52, height: 52)
        if round {
            art.clipShape(Circle())
        } else {
            art.clipShape(RoundedRectangle(cornerRadius: 4))
        }
    }

    private struct WeekRange {
        let start: String
        let end: String
        let label: String
    }

    private func weekRange(offset: Int) -> WeekRange {
        var cal = Calendar.current
        cal.firstWeekday = 2
        let today = Date()
        let weekday = cal.component(.weekday, from: today)
        let daysFromMonday = (weekday + 5) % 7
        guard let monday = cal.date(byAdding: .day, value: -daysFromMonday + offset * 7, to: today),
              let sunday = cal.date(byAdding: .day, value: 6, to: monday) else {
            return WeekRange(start: "", end: "", label: "")
        }
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM d, yyyy"
        let iso = DateFormatter()
        iso.dateFormat = "yyyy-MM-dd"
        return WeekRange(
            start: iso.string(from: monday),
            end: iso.string(from: sunday),
            label: "\(fmt.string(from: monday)) - \(fmt.string(from: sunday))"
        )
    }

    @State private var rankingRowsCache: [String: [RankingRow]] = [:]
    @State private var rankingWeekOffset = 0
    @State private var rankingYear = Calendar.current.component(.year, from: Date())

    private struct RankingRow: Identifiable {
        let id: String
        let headline: String
        let subtitle: String?
        let playsLabel: String
        let artist: String?
        let artPath: String?
    }

    private func rankingRows(for kind: SearchRankingKind) -> [RankingRow]? {
        rankingRowsCache[rankingCacheKey(kind)]
    }

    private func loadRankingRows(kind: SearchRankingKind) async {
        let key = rankingCacheKey(kind)
        if rankingRowsCache[key] != nil { return }
        let analytics: AnalyticsResponse?
        if kind == .bestOf {
            analytics = try? await appState.repository.analytics(from: "\(rankingYear)-01-01", to: "\(rankingYear)-12-31")
        } else {
            let range = weekRange(offset: rankingWeekOffset)
            analytics = try? await appState.repository.analytics(from: range.start, to: range.end)
        }
        guard let analytics else { return }
        let playLabel = { (n: Int) in n == 1 ? "1 play" : "\(n) plays" }
        let rows: [RankingRow]
        switch kind {
        case .artists:
            rows = analytics.topArtists.prefix(50).map {
                RankingRow(id: $0.id, headline: $0.displayName, subtitle: nil, playsLabel: playLabel($0.count), artist: nil, artPath: nil)
            }
        case .albums:
            rows = analytics.topAlbums.prefix(50).map {
                RankingRow(
                    id: $0.id,
                    headline: $0.artist ?? $0.displayName,
                    subtitle: $0.artist == nil ? nil : $0.displayName,
                    playsLabel: playLabel($0.count),
                    artist: $0.artist,
                    artPath: nil
                )
            }
        case .tracks, .bestOf:
            rows = analytics.topTracks.prefix(50).map {
                RankingRow(
                    id: $0.id,
                    headline: $0.displayName,
                    subtitle: $0.artist,
                    playsLabel: playLabel($0.count),
                    artist: $0.artist,
                    artPath: nil
                )
            }
        }
        rankingRowsCache[key] = rows
    }

    @ViewBuilder
    private func rankingRowLink(index: Int, row: RankingRow, kind: SearchRankingKind) -> some View {
        switch kind {
        case .artists:
            NavigationLink(value: SearchRoute.artist(row.headline)) {
                rankingRowLabel(index: index, row: row, kind: kind)
            }
            .simultaneousGesture(TapGesture().onEnded { recordSelection(.fromArtist(row.headline)) })
        case .albums:
            NavigationLink(value: SearchRoute.album(name: row.headline, artist: row.artist ?? row.subtitle)) {
                rankingRowLabel(index: index, row: row, kind: kind)
            }
            .simultaneousGesture(TapGesture().onEnded {
                recordSelection(.fromAlbum(row.headline, artist: row.artist ?? row.subtitle))
            })
        default:
            Button { openRankingRow(row, kind: kind) } label: {
                rankingRowLabel(index: index, row: row, kind: kind)
            }
            .buttonStyle(.plain)
        }
    }

    private func openRankingRow(_ row: RankingRow, kind: SearchRankingKind) {
        switch kind {
        case .artists, .albums:
            break
        case .tracks, .bestOf:
            Task {
                if let songs = try? await appState.repository.songs(page: 1, limit: 5, search: row.headline, artist: row.artist),
                   let path = songs.items.first?.path {
                    appState.play(.song(path: path, title: row.headline))
                } else if let artist = row.artist {
                    appState.play(.artist(name: artist))
                }
            }
        }
    }

    private func loadBrowseFeed() async {
        if let cached = SearchBrowseSessionCache.getIfFresh() {
            browseFeed = cached
            browseLoading = false
            return
        }
        browseLoading = browseFeed == nil
        defer { browseLoading = false }
        let feed = await SearchBrowseLoader.load(repository: appState.repository)
        SearchBrowseSessionCache.put(feed)
        browseFeed = feed
    }

    private func playGenreRadio(_ genre: GenreItem) async {
        let seedArtist = (try? await appState.repository.songs(page: 1, limit: 8, search: genre.name))?
            .items.compactMap(\.artist).first { !$0.isEmpty } ?? genre.name
        appState.play(.radio(displayTitle: "\(genre.name) Radio", seedKind: .genre, name: seedArtist, path: nil))
    }
}

private struct BrowseArtwork: View {
    let path: String
    let repository: BockMediaRepository
    let title: String
    @State private var url: URL?

    var body: some View {
        Group {
            if let url {
                BockArtwork(url: url, size: 200, cornerRadius: 0)
            } else {
                Color(BockColors.surfaceVariant)
                    .overlay {
                        Text(String(title.prefix(1)).uppercased())
                            .font(.title.bold())
                            .foregroundStyle(BockColors.green)
                    }
            }
        }
        .task(id: path) {
            if let urlStr = await repository.artworkURL(for: path), let u = URL(string: urlStr) {
                url = u
            }
        }
    }
}

private struct AddToRoomContext: Identifiable {
    let path: String
    let title: String
    let artist: String?
    var id: String { path }
}

private struct SearchResultArtwork: View {
    let repository: BockMediaRepository
    let title: String
    let artPath: String?
    var artistName: String?
    var albumName: String?
    var albumArtist: String?
    var circular: Bool = false

    @State private var url: URL?

    var body: some View {
        BockArtwork(
            url: url,
            size: 48,
            cornerRadius: circular ? 24 : 6
        )
        .task(id: taskKey) {
            url = await resolveURL()
        }
    }

    private var taskKey: String {
        [title, artPath ?? "", artistName ?? "", albumName ?? ""].joined(separator: "|")
    }

    private func resolveURL() async -> URL? {
        if let artPath, let str = await repository.artworkURL(for: artPath), let u = URL(string: str) {
            return u
        }
        if let artistName,
           let path = await repository.artistPortraitPath(for: artistName),
           let str = await repository.artworkURL(for: path),
           let u = URL(string: str) {
            return u
        }
        if let albumName {
            let albums = try? await repository.albums(page: 1, limit: 8, search: albumName, artist: albumArtist)
            if let path = albums?.items.first?.artPath,
               let str = await repository.artworkURL(for: path),
               let u = URL(string: str) {
                return u
            }
        }
        return nil
    }
}
