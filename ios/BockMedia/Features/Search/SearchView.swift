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
    @State private var recentQueries: [String] = []
    @State private var showNewReleases = false
    @State private var browseArtworkEpoch = 0
    @State private var addToPlaylist: AddToPlaylistContext?
    @State private var searchTask: Task<Void, Never>?
    @State private var favoritePaths: Set<String> = []

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }
    private var showResults: Bool { trimmedQuery.count >= 2 && results != nil }
    private var showSuggestions: Bool { !showResults && !trimmedQuery.isEmpty }
    private var showRecents: Bool { !showResults && !showSuggestions && searchFocused }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabScreenHeader(title: "Search")
            searchField
                .padding(.horizontal, 16)

            if loading && showResults {
                LoadingBox(logoSize: 40)
                    .padding()
            } else if showResults, let results {
                searchResultsList(results)
            } else if showSuggestions {
                suggestionsList
            } else if showRecents {
                recentQueriesList
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
            recentQueries = SearchHistoryStore.queries()
            if let favs = try? await appState.repository.favorites() {
                favoritePaths = Set(favs.map(\.path))
            }
            await loadBrowseFeed()
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
            searchTask?.cancel()
            searchTask = Task { await handleQueryChange() }
        }
    }

    private var browseScroll: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let picked = browseFeed?.pickedForYou, !picked.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Picked for you")
                            .font(.title3.bold())
                            .foregroundStyle(BockColors.onSurface)
                            .padding(.horizontal, 16)
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 10) {
                                ForEach(picked) { card in
                                    HomeCardRow(
                                        appState: appState,
                                        card: card,
                                        artworkEpoch: browseArtworkEpoch
                                    )
                                }
                            }
                            .padding(.horizontal, 16)
                        }
                    }
                }

                if let feed = browseFeed {
                    browseAllSection(feed)
                }
            }
            .padding(.bottom, 24)
        }
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
                    SearchHistoryStore.add(suggestion.title)
                    recentQueries = SearchHistoryStore.queries()
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

    private var recentQueriesList: some View {
        List {
            Section("Recent searches") {
                ForEach(recentQueries, id: \.self) { q in
                    Button {
                        query = q
                    } label: {
                        HStack {
                            BockIcon(icon: .history, size: 18)
                                .foregroundStyle(BockColors.muted)
                            Text(q).foregroundStyle(BockColors.onSurface)
                            Spacer()
                            Button {
                                SearchHistoryStore.remove(q)
                                recentQueries = SearchHistoryStore.queries()
                            } label: {
                                BockIcon(icon: .close, size: 16)
                                    .foregroundStyle(BockColors.muted)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
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
            if !results.playlists.isEmpty {
                Section("Playlists") {
                    ForEach(results.playlists, id: \.displayId) { hit in
                        searchRow(hit.name ?? "Playlist") {
                            if let id = hit.id, let name = hit.name {
                                appState.play(.playlist(id: id, name: name))
                            }
                        }
                    }
                }
            }
            if !results.artists.isEmpty {
                Section("Artists") {
                    ForEach(results.artists, id: \.displayId) { hit in
                        if let name = hit.name {
                            NavigationLink(value: SearchRoute.artist(name)) {
                                searchLabel(name)
                            }
                        }
                    }
                }
            }
            if !results.albums.isEmpty {
                Section("Albums") {
                    ForEach(results.albums, id: \.displayId) { hit in
                        if let name = hit.name {
                            NavigationLink(value: SearchRoute.album(name: name, artist: hit.artist)) {
                                VStack(alignment: .leading) {
                                    searchLabel(name)
                                    if let artist = hit.artist {
                                        Text(artist).font(.caption).foregroundStyle(BockColors.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if !results.songs.isEmpty {
                Section("Songs") {
                    ForEach(results.songs, id: \.displayId) { hit in
                        songRow(hit)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
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
        if let response = try? await appState.repository.search(q: q) {
            results = response
            suggestions = SearchBrowseLoader.suggestionsFromResponse(response)
            SearchHistoryStore.add(q)
            recentQueries = SearchHistoryStore.queries()
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
        SearchHistoryStore.add(suggestion.title)
        recentQueries = SearchHistoryStore.queries()
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
