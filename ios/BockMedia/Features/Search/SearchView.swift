import SwiftUI

struct SearchView: View {
    @ObservedObject var appState: AppState
    @FocusState private var searchFocused: Bool

    @State private var query = ""
    @State private var results: SearchResponse?
    @State private var suggestions: [SearchSuggestion] = []
    @State private var browseFeed: SearchBrowseFeed?
    @State private var browseLoading = true
    @State private var loading = false
    @State private var recentSelections: [SearchRecentSelection] = []
    @State private var showNewReleases = false
    @State private var browseArtworkEpoch = 0
    @State private var addToPlaylist: AddToPlaylistContext?
    @State private var searchTask: Task<Void, Never>?
    @State private var favoritePaths: Set<String> = []
    @State private var searchPins: [SearchPin] = []
    @State private var rankingKind: SearchRankingKind?
    @State private var expandedSections: Set<String> = []
    @State private var expandedResults: [String: SearchResponse] = [:]

    private enum SearchRankingKind: String, CaseIterable {
        case artists = "Top Artists"
        case albums = "Top Albums"
        case tracks = "Top Tracks"
        case bestOf = "Best Of…"
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }
    private var showResults: Bool { trimmedQuery.count >= 2 }
    private var showSuggestions: Bool { !showResults && !trimmedQuery.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabScreenHeader(title: "Search")
            searchField
                .padding(.horizontal, 16)

            if loading && showResults && results == nil {
                LoadingBox(logoSize: 40)
                    .padding()
            } else if showResults, let results {
                searchResultsList(results)
            } else if showResults {
                LoadingBox(logoSize: 40).padding()
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
            if let favs = try? await appState.repository.favorites() {
                favoritePaths = Set(favs.map(\.path))
            }
            searchPins = (try? await appState.repository.searchPins()) ?? []
            await loadBrowseFeed()
        }
        .onAppear {
            Task {
                searchPins = (try? await appState.repository.searchPins()) ?? []
            }
        }
        .sheet(item: $addToPlaylist) { ctx in
            AddToPlaylistSheet(
                appState: appState,
                trackPath: ctx.path,
                trackTitle: ctx.title,
                onDismiss: { addToPlaylist = nil },
                onAdded: { msg in
                    appState.toast = msg
                    addToPlaylist = nil
                }
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
                .onSubmit { Task { await runSearch() } }
            if !query.isEmpty {
                Button {
                    query = ""
                    results = nil
                    suggestions = []
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
        // Uses existing discovery modal flow on web; on iOS open search with explore hint.
        showNewReleases = true
    }

    private func openMixMuse() {
        rankingKind = .bestOf
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

    @ViewBuilder
    private func searchResultsList(_ results: SearchResponse) -> some View {
        List {
            searchSection("Tracks", key: "songs", hits: songHits(from: results), results: results)
            searchSection("Artists", key: "artists", hits: artistHits(from: results), results: results)
            searchSection("Albums", key: "albums", hits: albumHits(from: results), results: results)
            searchSection("Radio", key: "radios", hits: radioHits(from: results), results: results)
            searchSection("Sonically similar", key: "similar", hits: similarHits(from: results), results: results)
            searchSection("Playlists", key: "playlists", hits: playlistHits(from: results), results: results)
            searchSection("Smart playlists", key: "smartPlaylists", hits: smartHits(from: results), results: results)
            searchSection("Genres", key: "genres", hits: genreHits(from: results), results: results)
            searchSection("Messages", key: "messages", hits: messageHits(from: results), results: results)
            searchSection("Rooms", key: "rooms", hits: roomHits(from: results), results: results)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    private func searchSection(_ title: String, key: String, hits: [SearchHit], results: SearchResponse) -> some View {
        if !hits.isEmpty {
            Section(title) {
                ForEach(hits, id: \.displayId) { hit in
                    searchHitRow(hit, sectionKey: key)
                }
                if let total = results.counts[key], total > hits.count, !expandedSections.contains(key) {
                    Button("Show all \(total)") {
                        expandedSections.insert(key)
                        Task { await expandSection(key) }
                    }
                    .foregroundStyle(BockColors.green)
                }
            }
        }
    }

    @ViewBuilder
    private func searchHitRow(_ hit: SearchHit, sectionKey: String) -> some View {
        switch sectionKey {
        case "radios":
            let title = hit.name ?? hit.title ?? "Radio"
            Button { playRadioHit(hit) } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text(title).foregroundStyle(BockColors.onSurface)
                        Text("Radio").font(.caption).foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    BockIcon(icon: .playArrow, size: 22).foregroundStyle(BockColors.green)
                }
            }
        case "similar", "songs", "messages":
            songRow(hit)
        case "artists":
            if let name = hit.name {
                NavigationLink(value: SearchRoute.artist(name)) {
                    searchLabel(name)
                }
                .simultaneousGesture(TapGesture().onEnded {
                    recordSelection(.fromHit(kind: "artist", hit: hit))
                })
            }
        case "albums":
            if let name = hit.name {
                NavigationLink(value: SearchRoute.album(name: name, artist: hit.artist)) {
                    VStack(alignment: .leading) {
                        searchLabel(name)
                        if let artist = hit.artist {
                            Text(artist).font(.caption).foregroundStyle(BockColors.muted)
                        }
                    }
                }
                .simultaneousGesture(TapGesture().onEnded {
                    recordSelection(.fromHit(kind: "album", hit: hit))
                })
            }
        case "genres":
            if let name = hit.name {
                NavigationLink(value: SearchRoute.genre(name)) { searchLabel(name) }
            }
        case "playlists", "smartPlaylists":
            searchRow(hit.name ?? "Playlist") {
                if let id = hit.id, let name = hit.name {
                    appState.play(.playlist(id: id, name: name))
                }
            }
        default:
            searchLabel(hit.name ?? hit.title ?? "")
        }
    }

    private func radioHits(from results: SearchResponse) -> [SearchHit] {
        let src = expandedSections.contains("radios") ? (expandedResults["radios"] ?? results) : results
        return src.radios.map {
            SearchHit(name: $0.displayTitle ?? $0.name, artist: $0.artist, path: $0.path, title: $0.displayTitle)
        }
    }

    private func similarHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("similar") ? (expandedResults["similar"]?.similar ?? results.similar) : results.similar
    }

    private func playlistHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("playlists") ? (expandedResults["playlists"]?.playlists ?? results.playlists) : results.playlists
    }

    private func smartHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("smartPlaylists") ? (expandedResults["smartPlaylists"]?.smartPlaylists ?? results.smartPlaylists) : results.smartPlaylists
    }

    private func artistHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("artists") ? (expandedResults["artists"]?.artists ?? results.artists) : results.artists
    }

    private func albumHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("albums") ? (expandedResults["albums"]?.albums ?? results.albums) : results.albums
    }

    private func genreHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("genres") ? (expandedResults["genres"]?.genres ?? results.genres) : results.genres
    }

    private func songHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("songs") ? (expandedResults["songs"]?.songs ?? results.songs) : results.songs
    }

    private func messageHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("messages") ? (expandedResults["messages"]?.messages ?? results.messages) : results.messages
    }

    private func roomHits(from results: SearchResponse) -> [SearchHit] {
        expandedSections.contains("rooms") ? (expandedResults["rooms"]?.rooms ?? results.rooms) : results.rooms
    }

    private func playRadioHit(_ hit: SearchHit) {
        let title = hit.title ?? hit.name ?? "Radio"
        appState.play(.radio(displayTitle: title, seedKind: .artist, name: hit.name ?? title, path: hit.path))
    }

    private func expandSection(_ key: String) async {
        guard let response = try? await appState.repository.search(
            q: trimmedQuery,
            limit: 50,
            preview: 50,
            section: key,
            source: nil
        ) else { return }
        expandedResults[key] = response
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

    private func songRow(_ hit: SearchHit) -> some View {
        let path = hit.path ?? ""
        let starred = favoritePaths.contains(path)
        return HStack {
            Button {
                if let path = hit.path {
                    appState.play(.song(path: path, title: hit.title ?? hit.name ?? path))
                }
            } label: {
                VStack(alignment: .leading) {
                    Text(hit.title ?? hit.name ?? "Song").foregroundStyle(BockColors.onSurface)
                    if let artist = hit.artist {
                        Text(artist).font(.caption).foregroundStyle(BockColors.muted)
                    }
                }
            }
            .buttonStyle(.plain)
            Spacer()
            if let path = hit.path {
                Button {
                    addToPlaylist = AddToPlaylistContext(path: path, title: hit.title ?? hit.name ?? path)
                } label: {
                    BockIcon(icon: .playlistAdd, size: 22).foregroundStyle(BockColors.muted)
                }
                .buttonStyle(.plain)
                Button {
                    Task { await toggleFavorite(path: path, hit: hit, starred: starred) }
                } label: {
                    BockIcon(icon: .star, size: 22)
                        .foregroundStyle(starred ? BockColors.green : BockColors.muted)
                }
                .buttonStyle(.plain)
                Button {
                    appState.play(.song(path: path, title: hit.title ?? hit.name ?? path))
                } label: {
                    BockIcon(icon: .playArrow, size: 22).foregroundStyle(BockColors.green)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func toggleFavorite(path: String, hit: SearchHit, starred: Bool) async {
        if starred {
            try? await appState.repository.removeFavorite(path: path)
        } else {
            try? await appState.repository.addFavorite(
                path: path,
                title: hit.title ?? hit.name,
                artist: hit.artist,
                album: hit.album
            )
        }
        if let favs = try? await appState.repository.favorites() {
            favoritePaths = Set(favs.map(\.path))
        }
    }

    private func searchRow(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                searchLabel(title)
                Spacer()
                BockIcon(icon: .playArrow, size: 22).foregroundStyle(BockColors.green)
            }
        }
    }

    private func searchLabel(_ title: String) -> some View {
        Text(title).foregroundStyle(BockColors.onSurface)
    }

    private func handleQueryChange() async {
        try? await Task.sleep(nanoseconds: 250_000_000)
        guard !Task.isCancelled else { return }
        let q = trimmedQuery
        if q.isEmpty {
            results = nil
            suggestions = []
            return
        }
        if q.count < 2 {
            results = nil
            suggestions = await SearchBrowseLoader.suggestOneChar(repository: appState.repository, query: q)
            return
        }
        await runSearch()
    }

    private func runSearch() async {
        let q = trimmedQuery
        guard q.count >= 2 else { return }
        loading = true
        defer { loading = false }
        if let response = try? await appState.repository.search(
            q: q,
            limit: 30,
            preview: 5,
            source: nil
        ) {
            results = response
            suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
        } else {
            results = nil
            suggestions = []
        }
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
                            Button { openRankingRow(row, kind: kind) } label: {
                                rankingRowLabel(index: index + 1, row: row, kind: kind)
                            }
                            .buttonStyle(.plain)
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

    private func openRankingRow(_ row: RankingRow, kind: SearchRankingKind) {
        switch kind {
        case .artists:
            recordSelection(.fromArtist(row.headline))
            rankingKind = nil
        case .albums:
            recordSelection(.fromAlbum(row.subtitle ?? row.headline, artist: row.artist))
            rankingKind = nil
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
        browseLoading = true
        defer { browseLoading = false }
        let feed = await SearchBrowseLoader.load(repository: appState.repository)
        browseFeed = feed
        warmBrowseArtwork(feed.pickedForYou)
    }

    private func warmBrowseArtwork(_ cards: [HomeCard]) {
        browseArtworkEpoch += 1
        Task {
            await HomeArtworkResolver.warmPlaylistCovers(
                repository: appState.repository,
                cards: cards
            )
            browseArtworkEpoch += 1
        }
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
