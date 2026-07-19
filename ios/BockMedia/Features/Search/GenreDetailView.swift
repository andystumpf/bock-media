import SwiftUI

struct GenreDetailView: View {
    @ObservedObject var appState: AppState
    let genreName: String

    @State private var albums: [AlbumItem] = []
    @State private var artists: [ArtistItem] = []
    @State private var tracks: [SongItem] = []
    @State private var playlists: [PlaylistSummary] = []
    @State private var heroArtURL: URL?
    @State private var loading = true

    private var trackCount: Int {
        max(albums.reduce(0) { $0 + max($1.track_count, 0) }, tracks.count)
    }

    var body: some View {
        ZStack {
            ArtBackdrop(url: heroArtURL)
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: genreName)
                if loading && albums.isEmpty && artists.isEmpty {
                    LoadingBox(size: 48)
                        .frame(maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            GenreHeroBanner(
                                genreName: genreName,
                                trackCount: trackCount,
                                artURL: heroArtURL,
                                remoteOk: appState.remoteOk,
                                onPlayRadio: playGenreRadio
                            )

                            if !tracks.isEmpty {
                                PlexampSectionHeader(title: "TOP TRACKS")
                                ForEach(Array(tracks.prefix(50).enumerated()), id: \.element.id) { index, track in
                                    if let path = track.path {
                                        BockTrackArtRow(
                                            appState: appState,
                                            title: track.title ?? path,
                                            subtitle: [track.artist, track.album].compactMap { $0 }.joined(separator: " · "),
                                            artPath: path,
                                            trackNumber: index + 1,
                                            durationSeconds: track.duration,
                                            onTap: {
                                                appState.play(.song(path: path, title: track.title ?? path))
                                            }
                                        )
                                    }
                                }
                            }

                            if !playlists.isEmpty {
                                PlexampSectionHeader(title: "SUGGESTED PLAYLISTS")
                                ForEach(playlists) { pl in
                                    genrePlaylistRow(pl)
                                }
                            }

                            if !albums.isEmpty {
                                PlexampSectionHeader(title: "TOP ALBUMS")
                                ForEach(albums) { album in
                                    NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                                        PlexampAlbumRow(appState: appState, album: album)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }

                            if !artists.isEmpty {
                                PlexampSectionHeader(title: "TOP ARTISTS")
                                ForEach(artists) { artist in
                                    NavigationLink(value: SearchRoute.artist(artist.name)) {
                                        genreArtistRow(artist)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .padding(.bottom, 24)
                    }
                }
            }
        }
        .accessibilityIdentifier(BockTestTags.genreDetailBody)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: genreName) { await load() }
    }

    private func playGenreRadio() {
        let seed = artists.first?.name ?? genreName
        appState.play(.radio(displayTitle: "\(genreName) Radio", seedKind: .genre, name: seed, path: nil))
    }

    private func genrePlaylistRow(_ pl: PlaylistSummary) -> some View {
        Button { appState.play(.playlist(id: pl.id, name: pl.name)) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(pl.name)
                        .font(.body)
                        .foregroundStyle(.white)
                    Text("\(pl.tracks) tracks")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.55))
                }
                Spacer()
                BockIcon(icon: .playArrow, size: 22)
                    .foregroundStyle(BockColors.green)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }

    private func genreArtistRow(_ artist: ArtistItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(artist.name)
                    .font(.body)
                    .foregroundStyle(.white)
                Text("\(artist.tracks) tracks")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
            }
            Spacer()
            BockIcon(icon: .playArrow, size: 22)
                .foregroundStyle(BockColors.green)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        async let songsTask = appState.repository.songs(page: 1, limit: 200, genre: genreName)
        async let genresTask = appState.repository.genres(limit: 200)
        async let historyTask = appState.repository.streamHistory(limit: 80)
        async let playlistsTask = appState.repository.playlists(limit: 200)

        let songs = (try? await songsTask)?.items ?? []
        tracks = songs
        _ = (try? await genresTask) ?? []

        albums = Dictionary(
            grouping: songs.filter { !($0.album?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) },
            by: { "\($0.album ?? "")|\($0.artist ?? "")" }
        )
        .map { _, group in
            let first = group[0]
            return AlbumItem(
                album: first.album ?? "",
                artist: first.artist,
                track_count: group.count,
                art_path: first.path
            )
        }
        .sorted { $0.track_count > $1.track_count }
        .prefix(12)
        .map { $0 }

        artists = songs
            .compactMap(\.artist)
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .reduce(into: [String: Int]()) { counts, name in
                counts[name, default: 0] += 1
            }
            .map { ArtistItem(artist: $0.key, track_count: $0.value) }
            .sorted { $0.track_count > $1.track_count }
            .prefix(12)
            .map { $0 }

        let history = (try? await historyTask)?.items ?? []
        let allPlaylists = (try? await playlistsTask)?.items ?? []

        var recentNames: [String] = []
        for row in history where HomeFeedRules.historyMatchesGenre(row, genre: genreName) {
            if let name = row.playlist?.trimmingCharacters(in: .whitespaces), !name.isEmpty,
               !recentNames.contains(where: { $0.caseInsensitiveCompare(name) == .orderedSame }) {
                recentNames.append(name)
            }
        }

        let byName = Dictionary(allPlaylists.map { ($0.name.lowercased(), $0) }, uniquingKeysWith: { _, last in last })
        var suggested: [PlaylistSummary] = recentNames.compactMap { byName[$0.lowercased()] }
        for pl in allPlaylists where pl.name.localizedCaseInsensitiveContains(genreName)
            || HomeFeedRules.isGenreMixPlaylistName(pl.name, genre: genreName) {
            if !suggested.contains(where: { $0.id == pl.id }) {
                suggested.append(pl)
            }
        }
        playlists = Array(suggested.prefix(12))

        if let path = tracks.first?.path ?? albums.first?.artPath ?? artists.first?.artPath,
           let str = await appState.repository.artworkURL(for: path),
           let url = URL(string: str) {
            heroArtURL = url
        }
    }
}

struct ArtistDetailView: View {
    @ObservedObject var appState: AppState
    let artistName: String

    @State private var resolvedArtist: String = ""
    @State private var studioAlbums: [AlbumItem] = []
    @State private var singleAlbums: [AlbumItem] = []
    @State private var allAlbums: [AlbumItem] = []
    @State private var appearsOnAlbums: [AlbumItem] = []
    @State private var appearsOnNames: Set<String> = []
    @State private var popularTracks: [ArtistPopularTrackRowModel] = []
    @State private var ratedTracks: [RatingItem] = []
    @State private var similarArtists: [String] = []
    @State private var genres: [String] = []
    @State private var relatedVideos: [MusicVideoRelatedItem] = []
    @State private var aboutInfo: ArtistDetailAbout?
    @State private var totalTrackCount = 0
    @State private var totalPlayCount = 0
    @State private var albumCount = 0
    @State private var followed = false
    @State private var albumSort: ArtistAlbumSort = .newest
    @State private var selectedTab = "music"
    @State private var collapsedSections: Set<String> = []
    @State private var artURL: URL?
    @State private var loading = true
    @State private var showPopularExpanded = false
    @State private var showMixMuse = false
    @State private var showMore = false
    @State private var showShare = false
    @State private var showDiscography = false
    @State private var showListenAgent = false
    @State private var listenAgentPrompt: String?
    @State private var trackMenu: ArtistPopularTrackRowModel?
    @State private var showStickyHeader = false

    private var discoverySeed: DiscoverySeed {
        DiscoverySeed(kind: .album, title: resolvedArtist.isEmpty ? artistName : resolvedArtist, album: nil, artist: resolvedArtist.isEmpty ? artistName : resolvedArtist)
    }

    private var heroAccent: Color {
        gradientAccentColor(for: resolvedArtist.isEmpty ? artistName : resolvedArtist)
    }

    private var sortedStudioAlbums: [AlbumItem] {
        ArtistDetailRules.sortAlbums(studioAlbums, sort: albumSort)
    }

    private var sortedSingleAlbums: [AlbumItem] {
        ArtistDetailRules.sortAlbums(singleAlbums, sort: albumSort)
    }

    private var latestAlbum: AlbumItem? {
        ArtistDetailRules.latestAlbum(allAlbums)
    }

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if loading && studioAlbums.isEmpty && singleAlbums.isEmpty {
                LoadingBox()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        scrollOffsetMarker
                        SpotifyArtistHeroBanner(
                            artistName: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                            artURL: artURL,
                            accentColor: heroAccent,
                            onMore: { showMore = true },
                            onListenAgent: {
                                listenAgentPrompt = "play top songs from \(resolvedArtist.isEmpty ? artistName : resolvedArtist)"
                                showListenAgent = true
                            }
                        )
                        ArtistLibraryStatsStrip(
                            trackCount: totalTrackCount,
                            albumCount: albumCount,
                            totalPlays: totalPlayCount
                        )
                        SpotifyArtistActions(
                            statLine: nil,
                            albumCount: albumCount,
                            followed: followed,
                            onPlay: { appState.play(.artist(name: resolvedArtist.isEmpty ? artistName : resolvedArtist)) },
                            onShuffle: {
                                let name = resolvedArtist.isEmpty ? artistName : resolvedArtist
                                appState.play(.radio(displayTitle: "\(name) Radio", seedKind: .artist, name: name, path: nil))
                            },
                            onRadio: { Task { await runResonanceRadio() } },
                            onFollowToggle: { Task { await toggleFollow() } }
                        )
                        ArtistSectionTabs(selected: selectedTab) { tab in
                            selectedTab = tab
                            persistUiState()
                        }

                        if selectedTab == "about" {
                            ArtistAboutSection(
                                about: aboutInfo,
                                artistName: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                                trackCount: totalTrackCount
                            )
                        }

                        if selectedTab == "music" {
                            musicTabContent
                        }
                    }
                    .padding(.bottom, 24)
                }
                .coordinateSpace(name: "artistScroll")
                .onPreferenceChange(ScrollOffsetPreferenceKey.self) { offset in
                    showStickyHeader = offset < -180
                }
            }

            if showStickyHeader {
                DetailStickyMiniHeader(title: resolvedArtist.isEmpty ? artistName : resolvedArtist) {
                    appState.play(.artist(name: resolvedArtist.isEmpty ? artistName : resolvedArtist))
                }
            }
        }
        .accessibilityIdentifier(BockTestTags.artistDetailBody)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showMore) {
            DetailEntitySheet(
                appState: appState,
                title: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                rating: RatingTarget(
                    kind: .artist,
                    id: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                    title: resolvedArtist.isEmpty ? artistName : resolvedArtist
                ),
                actions: [
                    DetailSheetAction(label: "Share…", systemImage: "link") {
                        showMore = false
                        showShare = true
                    },
                    DetailSheetAction(label: "Resonance radio", systemImage: "waveform") {
                        showMore = false
                        Task { await runResonanceRadio() }
                    },
                    DetailSheetAction(label: "Mix Muse playlist…", systemImage: "sparkles") {
                        showMore = false
                        showMixMuse = true
                    },
                ],
                onDismiss: { showMore = false }
            )
        }
        .sheet(isPresented: $showShare) {
            DetailShareSheet(
                appState: appState,
                title: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                deepLink: artistDeepLink,
                onDismiss: { showShare = false }
            )
        }
        .sheet(item: $trackMenu) { track in
            if let path = track.path {
                DetailEntitySheet(
                    appState: appState,
                    title: track.title,
                    rating: RatingTarget(
                        kind: .song,
                        id: path,
                        title: track.title,
                        artist: track.artist ?? resolvedArtist,
                        album: track.album
                    ),
                    actions: [
                        DetailSheetAction(label: "Play", systemImage: "play.fill") {
                            appState.play(.song(path: path, title: track.title))
                        },
                    ],
                    onDismiss: { trackMenu = nil }
                )
            }
        }
        .navigationDestination(isPresented: $showDiscography) {
            ArtistDiscographyPlaceholderView(
                appState: appState,
                artistName: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                albums: ArtistDiscographyNavCache.albums,
                appearsOnNames: ArtistDiscographyNavCache.appearsOnNames
            )
        }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: discoverySeed)
        }
        .fullScreenCover(isPresented: $showListenAgent) {
            ListenAgentView(
                appState: appState,
                autoStartListening: true,
                initialPrompt: listenAgentPrompt,
                autoSubmitPrompt: false
            )
        }
        .task(id: artistName) { await load() }
        .onChange(of: selectedTab) { _, _ in persistUiState() }
        .onChange(of: collapsedSections) { _, _ in persistUiState() }
    }

    @ViewBuilder
    private var musicTabContent: some View {
        if let latestAlbum {
            NavigationLink(value: SearchRoute.album(name: latestAlbum.name, artist: latestAlbum.artist ?? resolvedArtist)) {
                ArtistLatestAlbumCard(
                    appState: appState,
                    album: latestAlbum,
                    artistName: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                    onTap: {}
                )
            }
            .buttonStyle(.plain)
        }

        if !ratedTracks.isEmpty {
            SpotifyRatedRow(
                artistName: resolvedArtist.isEmpty ? artistName : resolvedArtist,
                artURL: artURL,
                ratedCount: ratedTracks.count,
                onTap: {
                    Task {
                        await appState.repository.playDiscoveryTracksLocally(
                            ratedTracks.map {
                                PlaylistTrack(title: $0.title, artist: $0.artist, album: $0.album, path: $0.id)
                            },
                            title: "Highly rated · \(resolvedArtist.isEmpty ? artistName : resolvedArtist)",
                            shuffle: true
                        )
                        appState.showNowPlayingSheet = true
                    }
                }
            )
        }

        if !popularTracks.isEmpty {
            CollapsibleSectionHeader(
                title: "Popular",
                expanded: !collapsedSections.contains("popular"),
                onToggle: { toggleSection("popular") },
                onPlay: { Task { await playAllPopularTracks() } },
                playAccessibilityLabel: "Play all popular tracks"
            )
            if !collapsedSections.contains("popular") {
                let visible = showPopularExpanded ? popularTracks : Array(popularTracks.prefix(5))
                ForEach(visible) { track in
                    ArtistPopularTrackRow(
                        appState: appState,
                        rank: track.rank,
                        title: track.title,
                        subtitle: track.subtitle,
                        artPath: track.path,
                        liked: track.liked,
                        onTap: {
                            if let path = track.path {
                                appState.play(.song(path: path, title: track.title))
                            }
                        },
                        onMenu: { trackMenu = track },
                        onLikeToggle: { Task { await toggleTrackLike(track) } }
                    )
                }
                if !showPopularExpanded && popularTracks.count > 5 {
                    Button("See all") { showPopularExpanded = true }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(BockColors.green)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                }
            }
        }

        if !relatedVideos.isEmpty {
            SpotifySectionTitle(title: "Videos")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(relatedVideos) { video in
                        ArtistVideoCard(video: video) {
                            if let match = popularTracks.first(where: { video.title.localizedCaseInsensitiveContains($0.title) }),
                               let path = match.path {
                                appState.play(.song(path: path, title: match.title))
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }

        if !sortedStudioAlbums.isEmpty {
            HStack(alignment: .center) {
                SpotifySectionTitle(title: "Albums", subtitle: ArtistDetailRules.yearRange(sortedStudioAlbums))
                Spacer()
                Button("See all") {
                    ArtistDiscographyNavCache.albums = allAlbums.isEmpty ? sortedStudioAlbums + sortedSingleAlbums : allAlbums
                    ArtistDiscographyNavCache.appearsOnNames = appearsOnNames
                    showDiscography = true
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BockColors.green)
                .padding(.trailing, 16)
            }
            ArtistAlbumSortPicker(sort: $albumSort)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(sortedStudioAlbums) { album in
                        NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                            ArtistDiscographyCard(appState: appState, album: album)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }

        if !sortedSingleAlbums.isEmpty {
            SpotifySectionTitle(title: "Singles & EPs", subtitle: ArtistDetailRules.yearRange(sortedSingleAlbums))
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(sortedSingleAlbums) { album in
                        NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                            ArtistDiscographyCard(appState: appState, album: album)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }

        if !appearsOnAlbums.isEmpty {
            SpotifySectionTitle(title: "Appears on")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(appearsOnAlbums) { album in
                        NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                            ArtistDiscographyCard(appState: appState, album: album)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }

        if !similarArtists.isEmpty {
            SpotifySectionTitle(title: "Fans also like")
            ForEach(similarArtists, id: \.self) { name in
                NavigationLink(value: SearchRoute.artist(name)) {
                    ArtistSimilarArtistRow(appState: appState, name: name)
                }
                .buttonStyle(.plain)
            }
        }

        if !genres.isEmpty {
            SpotifySectionTitle(title: "Genres")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(genres, id: \.self) { genre in
                        NavigationLink(value: SearchRoute.genre(genre)) {
                            Text(genre)
                                .font(.caption)
                                .foregroundStyle(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.white.opacity(0.12))
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    private var scrollOffsetMarker: some View {
        GeometryReader { geo in
            Color.clear.preference(
                key: ScrollOffsetPreferenceKey.self,
                value: geo.frame(in: .named("artistScroll")).minY
            )
        }
        .frame(height: 0)
    }

    private var artistDeepLink: String {
        let name = resolvedArtist.isEmpty ? artistName : resolvedArtist
        let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
        return "bockmedia://artist/\(encoded)"
    }

    private func toggleSection(_ id: String) {
        if collapsedSections.contains(id) {
            collapsedSections.remove(id)
        } else {
            collapsedSections.insert(id)
        }
    }

    private func persistUiState() {
        let name = resolvedArtist.isEmpty ? artistName : resolvedArtist
        ArtistDetailSessionCache.saveUiState(
            name,
            ui: ArtistDetailSessionCache.UiState(
                collapsedSections: collapsedSections,
                selectedTab: selectedTab
            )
        )
    }

    private func applyDetail(_ detail: ArtistDetailResponse, portraitPath: String?, rated: [RatingItem]) {
        resolvedArtist = detail.artist.isEmpty ? artistName : detail.artist
        followed = detail.followed
        ratedTracks = ArtistDetailRules.ratedTracks(for: resolvedArtist, in: rated)
        let albums = detail.albums.map { $0.asAlbumItem() }
        let split = ArtistDetailRules.splitAlbums(albums)
        studioAlbums = split.studio
        singleAlbums = split.singles
        allAlbums = albums
        appearsOnAlbums = detail.appearsOn.map { $0.asAlbumItem() }
        appearsOnNames = Set(appearsOnAlbums.map(\.name))
        aboutInfo = detail.about
        totalTrackCount = detail.trackCount > 0 ? detail.trackCount : albums.reduce(0) { $0 + $1.tracks }
        totalPlayCount = detail.totalPlays
        albumCount = detail.albumCount > 0 ? detail.albumCount : albums.count
        popularTracks = detail.topTracks.enumerated().map { index, track in
            ArtistPopularTrackRowModel(
                rank: index + 1,
                title: track.title ?? "Track",
                path: track.path,
                album: track.album,
                artist: track.artist,
                playCount: track.playCount,
                duration: track.duration,
                liked: track.liked,
                rating: track.rating
            )
        }
        similarArtists = detail.similarArtists.map(\.artist).filter { !$0.isEmpty && $0.localizedCaseInsensitiveCompare(resolvedArtist) != .orderedSame }
        genres = ArtistDetailRules.topGenres(from: detail.topTracks)
    }

    private func load() async {
        showPopularExpanded = false
        resolvedArtist = artistName
        if let ui = ArtistDetailSessionCache.getUiState(artistName) {
            selectedTab = ui.selectedTab
            collapsedSections = ui.collapsedSections
        }
        if let cached = ArtistDetailSessionCache.get(artistName) {
            applyDetail(cached.0, portraitPath: cached.1, rated: [])
            loading = false
        } else {
            loading = studioAlbums.isEmpty && singleAlbums.isEmpty
        }

        async let detailTask = appState.repository.artistDetail(name: artistName)
        async let portraitTask = appState.repository.artistPortraitPath(for: artistName)
        async let ratedTask = appState.repository.ratedSongs()
        async let videosTask = (try? await appState.repository.musicVideoRelated(artist: artistName))?.items ?? []

        do {
            let detail = try await detailTask
            let portrait = await portraitTask
            let rated = (try? await ratedTask) ?? []
            relatedVideos = await videosTask
            applyDetail(detail, portraitPath: portrait, rated: rated)
            ArtistDetailSessionCache.put(artistName, detail: detail, portraitPath: portrait)
            if let portrait,
               let str = await appState.repository.artworkURL(for: portrait),
               let url = URL(string: str) {
                artURL = url
            }
        } catch {
            appState.toast = error.localizedDescription
        }
        loading = false
    }

    private func toggleFollow() async {
        let name = resolvedArtist.isEmpty ? artistName : resolvedArtist
        let nextStars = followed ? 0 : 3
        do {
            try await appState.repository.setRating(kind: .artist, id: name, stars: nextStars, title: name)
            followed = nextStars >= 3
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func toggleTrackLike(_ track: ArtistPopularTrackRowModel) async {
        guard let path = track.path else { return }
        let next = track.liked ? 0 : 5
        do {
            try await appState.repository.setRating(
                kind: .song,
                id: path,
                stars: next,
                title: track.title,
                artist: track.artist,
                album: track.album
            )
            popularTracks = popularTracks.map {
                guard $0.id == track.id else { return $0 }
                var copy = $0
                copy.liked = next >= 5
                copy.rating = next
                return copy
            }
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func playAllPopularTracks() async {
        let name = resolvedArtist.isEmpty ? artistName : resolvedArtist
        let tracks = popularTracks.compactMap { track -> PlaylistTrack? in
            guard let path = track.path, !path.isEmpty else { return nil }
            return PlaylistTrack(title: track.title, artist: track.artist ?? name, album: track.album, path: path)
        }
        guard !tracks.isEmpty else {
            appState.toast = "No playable tracks"
            return
        }
        do {
            await appState.repository.playDiscoveryTracksLocally(
                tracks,
                title: "Popular · \(name)",
                shuffle: false
            )
            appState.showNowPlayingSheet = true
        } catch {
            appState.toast = error.localizedDescription
        }
    }

    private func runResonanceRadio() async {
        do {
            let resp = try await appState.repository.resonanceRadio(
                seedKind: discoverySeed.kind.rawValue,
                album: discoverySeed.album, artist: discoverySeed.artist
            )
            await appState.repository.playDiscoveryTracksLocally(resp.tracks, title: resp.name ?? discoverySeed.title, shuffle: true)
            appState.showNowPlayingSheet = true
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

private struct ArtistPopularTrackRowModel: Identifiable {
    let id: String
    let rank: Int
    let title: String
    var path: String?
    var album: String?
    var artist: String?
    var playCount: Int?
    var duration: Int?
    var liked: Bool
    var rating: Int

    init(
        rank: Int,
        title: String,
        path: String?,
        album: String?,
        artist: String?,
        playCount: Int?,
        duration: Int?,
        liked: Bool,
        rating: Int
    ) {
        self.id = path ?? "\(rank)-\(title)"
        self.rank = rank
        self.title = title
        self.path = path
        self.album = album
        self.artist = artist
        self.playCount = playCount
        self.duration = duration
        self.liked = liked
        self.rating = rating
    }

    var subtitle: String? {
        if let playCount, playCount > 0 { return formatArtistPlayCount(playCount) + " plays" }
        if let album, !album.isEmpty { return album }
        if let duration { return PlexampFormat.trackDuration(duration) }
        return nil
    }
}

private func formatArtistPlayCount(_ count: Int) -> String {
    if count >= 1_000_000_000 { return String(format: "%.1fB", Double(count) / 1_000_000_000) }
    if count >= 1_000_000 { return String(format: "%.1fM", Double(count) / 1_000_000) }
    if count >= 10_000 { return count.formatted() }
    return "\(count)"
}

private struct ArtistAlbumSortPicker: View {
    @Binding var sort: ArtistAlbumSort

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(ArtistAlbumSort.allCases, id: \.self) { option in
                    Button(option.label) { sort = option }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(sort == option ? BockColors.green : .white.opacity(0.75))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(sort == option ? BockColors.green.opacity(0.25) : Color.white.opacity(0.12))
                        .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
        }
    }
}

private struct ArtistDiscographyCard: View {
    @ObservedObject var appState: AppState
    let album: AlbumItem
    @State private var artURL: URL?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            BockArtwork(url: artURL, size: 140, cornerRadius: 6)
            Text(album.name)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .frame(width: 140, alignment: .leading)
            Text([album.year.map(String.init), "\(album.tracks) tracks"].compactMap { $0 }.joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.white.opacity(0.55))
                .lineLimit(1)
        }
        .frame(width: 140)
        .task(id: album.artPath) {
            if let path = album.artPath,
               let str = await appState.repository.artworkURL(for: path),
               let url = URL(string: str) {
                artURL = url
            }
        }
    }
}

private struct ArtistVideoCard: View {
    let video: MusicVideoRelatedItem
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 6) {
                if let thumb = video.thumbnail, let url = URL(string: thumb) {
                    BockArtwork(url: url, size: 160, cornerRadius: 6)
                        .frame(width: 160, height: 90)
                }
                Text(video.title)
                    .font(.caption)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .frame(width: 160, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
    }
}

private extension Array {
    func uniqued(by key: (Element) -> String) -> [Element] {
        var seen = Set<String>()
        return filter { seen.insert(key($0)).inserted }
    }
}

private extension Array where Element: Hashable {
    func uniqued() -> [Element] {
        var seen = Set<Element>()
        return filter { seen.insert($0).inserted }
    }
}

struct AlbumDetailView: View {
    @ObservedObject var appState: AppState
    let albumName: String
    let artist: String?

    @State private var tracks: [SongItem] = []
    @State private var albumMeta: AlbumItem?
    @State private var artURL: URL?
    @State private var hotTrackTitles: Set<String> = []
    @State private var loading = true
    @State private var showMixMuse = false
    @State private var showMore = false
    @State private var showShare = false
    @State private var trackMenu: SongItem?
    @State private var showStickyHeader = false

    private var seed: DiscoverySeed {
        DiscoverySeed(kind: .album, title: albumName, album: albumName, artist: artist)
    }

    private var displayArtist: String? {
        artist ?? tracks.first?.artist ?? albumMeta?.artist
    }

    private var displayYear: Int? {
        albumMeta?.year ?? tracks.first?.year
    }

    private var isCompilation: Bool {
        ArtistDetailRules.isCompilationAlbumName(albumName, albumArtist: displayArtist)
    }

    private var discGroups: [AlbumDiscGroup] {
        groupTracksByDisc(tracks)
    }

    private var totalSeconds: Int {
        tracks.compactMap(\.duration).reduce(0, +)
    }

    var body: some View {
        ZStack(alignment: .top) {
            ArtBackdrop(url: artURL)
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: albumName)
                if loading && tracks.isEmpty {
                    LoadingBox()
                        .frame(maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            scrollOffsetMarker
                            albumHero
                            Divider().background(Color.white.opacity(0.12)).padding(.horizontal, 16)

                            if tracks.isEmpty {
                                Text("No tracks found")
                                    .foregroundStyle(.white.opacity(0.55))
                                    .padding(16)
                            } else {
                                ForEach(discGroups) { disc in
                                    let summaryTracks = discGroups.count == 1 ? tracks.count : disc.tracks.count
                                    let summarySeconds = discGroups.count == 1
                                        ? totalSeconds
                                        : disc.tracks.compactMap(\.duration).reduce(0, +)
                                    DiscHeaderRow(
                                        discNumber: disc.number,
                                        trackCount: summaryTracks,
                                        totalSeconds: summarySeconds,
                                        onPlayDisc: { Task { await playDisc(disc.tracks) } }
                                    )
                                    ForEach(Array(disc.tracks.enumerated()), id: \.element.id) { index, track in
                                        PlexampTrackRow(
                                            title: track.title ?? "Track",
                                            subtitle: isCompilation ? track.artist : nil,
                                            trackNumber: track.trackNumber ?? (index + 1),
                                            isHot: hotTrackTitles.contains(track.title?.trimmingCharacters(in: .whitespaces).lowercased() ?? ""),
                                            durationSeconds: track.duration
                                        ) {
                                            if let path = track.path {
                                                appState.play(.song(path: path, title: track.title ?? albumName))
                                            }
                                        } onMenu: {
                                            trackMenu = track
                                        }
                                    }
                                }
                            }
                        }
                        .padding(.bottom, 24)
                    }
                    .coordinateSpace(name: "albumScroll")
                    .onPreferenceChange(ScrollOffsetPreferenceKey.self) { offset in
                        showStickyHeader = offset < -120
                    }
                }
            }

            if showStickyHeader {
                DetailStickyMiniHeader(title: albumName) {
                    appState.play(.album(name: albumName, artist: artist))
                }
            }
        }
        .accessibilityIdentifier(BockTestTags.albumDetailBody)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showMore) {
            DetailEntitySheet(
                appState: appState,
                title: albumName,
                rating: RatingTarget(
                    kind: .album,
                    id: albumRatingId(album: albumName, artist: artist),
                    title: albumName,
                    artist: artist,
                    album: albumName
                ),
                actions: [
                    DetailSheetAction(label: "Share…", systemImage: "link") {
                        showMore = false
                        showShare = true
                    },
                    DetailSheetAction(label: "Resonance radio", systemImage: "waveform") {
                        showMore = false
                        Task { await runResonanceRadio() }
                    },
                    DetailSheetAction(label: "Mix Muse playlist…", systemImage: "sparkles") {
                        showMore = false
                        showMixMuse = true
                    },
                ],
                onDismiss: { showMore = false }
            )
        }
        .sheet(isPresented: $showShare) {
            DetailShareSheet(
                appState: appState,
                title: albumName,
                deepLink: albumDeepLink,
                onDismiss: { showShare = false }
            )
        }
        .sheet(item: $trackMenu) { track in
            if let path = track.path {
                DetailEntitySheet(
                    appState: appState,
                    title: track.title ?? "Track",
                    rating: RatingTarget(
                        kind: .song,
                        id: path,
                        title: track.title ?? "Track",
                        artist: track.artist,
                        album: track.album
                    ),
                    actions: [
                        DetailSheetAction(label: "Play", systemImage: "play.fill") {
                            appState.play(.song(path: path, title: track.title ?? albumName))
                        },
                    ],
                    onDismiss: { trackMenu = nil }
                )
            }
        }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: seed)
        }
        .task(id: "\(albumName)-\(artist ?? "")") { await load() }
    }

    private var scrollOffsetMarker: some View {
        GeometryReader { geo in
            Color.clear.preference(
                key: ScrollOffsetPreferenceKey.self,
                value: geo.frame(in: .named("albumScroll")).minY
            )
        }
        .frame(height: 0)
    }

    private var albumDeepLink: String {
        let encoded = albumName.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? albumName
        return "bockmedia://album/\(encoded)"
    }

    private var albumHero: some View {
        HStack(alignment: .top, spacing: 14) {
            BockArtwork(url: artURL, size: 120, cornerRadius: 6)
            VStack(alignment: .leading, spacing: 0) {
                if let displayArtist {
                    Text(displayArtist)
                        .font(.title2.weight(.bold))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                }
                if let displayYear {
                    Text(String(displayYear))
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.65))
                        .padding(.top, 2)
                }
                Spacer().frame(height: 14)
                PlayDownloadActions(
                    appState: appState,
                    target: .album(name: albumName, artist: artist)
                )
                HStack(spacing: 0) {
                    DetailHeroIconButton(icon: .shuffle) {
                        Task {
                            await LocalPlaybackController.shared.playTarget(
                                repository: appState.repository,
                                target: .album(name: albumName, artist: artist),
                                shuffle: true
                            )
                            if LocalPlaybackController.shared.state.error == nil {
                                appState.showNowPlayingSheet = true
                            }
                        }
                    }
                    DetailHeroIconButton(icon: .playArrow, systemImage: "waveform") {
                        Task { await runResonanceRadio() }
                    }
                    DetailHeroIconButton(icon: .moreVert) {
                        showMore = true
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        var response = try? await appState.repository.songs(
            page: 1, limit: 500, artist: artist, album: albumName
        )
        if response?.items.isEmpty != false {
            response = try? await appState.repository.songs(
                page: 1, limit: 500, search: albumName, artist: artist
            )
        }
        var loaded = sortAlbumTracks(response?.items ?? [])
        if let artist, !artist.isEmpty {
            let narrowed = loaded.filter { $0.artist?.localizedCaseInsensitiveCompare(artist) == .orderedSame }
            if !narrowed.isEmpty { loaded = sortAlbumTracks(narrowed) }
        }
        tracks = loaded

        albumMeta = try? await appState.repository.albums(
            page: 1, limit: 20, search: albumName, artist: artist
        ).items.first(where: { $0.name.localizedCaseInsensitiveCompare(albumName) == .orderedSame })

        let artPath = albumMeta?.artPath ?? tracks.first?.path
        if let artPath,
           let str = await appState.repository.artworkURL(for: artPath),
           let url = URL(string: str) {
            artURL = url
        }

        if let analytics = try? await appState.repository.analytics() {
            hotTrackTitles = Set(analytics.topTracks.compactMap {
                ($0.name ?? $0.label)?.trimmingCharacters(in: .whitespaces).lowercased()
            }.filter { !$0.isEmpty })
        }
    }

    private func playDisc(_ discTracks: [SongItem]) async {
        await appState.repository.playDiscoveryTracksLocally(
            discTracks.compactMap { track in
                guard let path = track.path else { return nil }
                return PlaylistTrack(title: track.title, artist: track.artist, album: track.album, path: path)
            },
            title: "\(albumName) · Disc \(discTracks.first?.discNumber ?? 1)",
            shuffle: false
        )
        appState.showNowPlayingSheet = true
    }

    private func runResonanceRadio() async {
        do {
            let resp = try await appState.repository.resonanceRadio(
                seedKind: seed.kind.rawValue,
                album: seed.album, artist: seed.artist
            )
            await appState.repository.playDiscoveryTracksLocally(resp.tracks, title: resp.name ?? seed.title, shuffle: true)
            appState.showNowPlayingSheet = true
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

private struct AlbumDiscGroup: Identifiable {
    let number: Int
    let tracks: [SongItem]
    var id: Int { number }
}

private func sortAlbumTracks(_ items: [SongItem]) -> [SongItem] {
    var seen = Set<String>()
    let unique = items.filter { track in
        let key = track.path ?? "\(track.discNumber ?? 1)|\(track.trackNumber ?? 0)|\(track.title ?? "")"
        return seen.insert(key).inserted
    }
    return unique.sorted { lhs, rhs in
        let d0 = lhs.discNumber ?? 1
        let d1 = rhs.discNumber ?? 1
        if d0 != d1 { return d0 < d1 }
        let t0 = lhs.trackNumber ?? Int.max
        let t1 = rhs.trackNumber ?? Int.max
        if t0 != t1 { return t0 < t1 }
        return (lhs.title ?? "").localizedCaseInsensitiveCompare(rhs.title ?? "") == .orderedAscending
    }
}

private func groupTracksByDisc(_ tracks: [SongItem]) -> [AlbumDiscGroup] {
    guard !tracks.isEmpty else { return [] }
    let grouped = Dictionary(grouping: tracks, by: { $0.discNumber ?? 1 })
    return grouped.keys.sorted().map { AlbumDiscGroup(number: $0, tracks: grouped[$0] ?? []) }
}

private struct ScrollOffsetPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

struct ArtistDiscographyPlaceholderView: View {
    @ObservedObject var appState: AppState
    let artistName: String
    let albums: [AlbumItem]
    let appearsOnNames: Set<String>

    @State private var filter: ArtistDiscographyFilter = .studio
    @State private var sort: ArtistAlbumSort = .newest

    private var filteredAlbums: [AlbumItem] {
        ArtistDetailRules.sortAlbums(
            ArtistDetailRules.filterDiscography(albums, filter: filter, appearsOnNames: appearsOnNames),
            sort: sort
        )
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: "\(artistName) — Discography")
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(ArtistDiscographyFilter.allCases, id: \.self) { option in
                            Button(option.label) { filter = option }
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(filter == option ? BockColors.green : .white.opacity(0.75))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(filter == option ? BockColors.green.opacity(0.25) : Color.white.opacity(0.12))
                                .clipShape(Capsule())
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
                ArtistAlbumSortPicker(sort: $sort)
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], spacing: 16) {
                        ForEach(filteredAlbums) { album in
                            NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                                ArtistDiscographyCard(appState: appState, album: album)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(16)
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }
}
