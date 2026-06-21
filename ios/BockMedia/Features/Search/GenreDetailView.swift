import SwiftUI

struct GenreDetailView: View {
    @ObservedObject var appState: AppState
    let genreName: String

    @State private var albums: [AlbumItem] = []
    @State private var artists: [ArtistItem] = []
    @State private var playlists: [PlaylistSummary] = []
    @State private var loading = true

    var body: some View {
        List {
            Section {
                Button {
                    let seed = artists.first?.name ?? genreName
                    appState.play(.radio(displayTitle: "\(genreName) Radio", seedKind: .genre, name: seed, path: nil))
                } label: {
                    HStack {
                        Spacer()
                        BockIcon(icon: .playArrow, size: 24)
                        Text("Play \(genreName) radio")
                            .fontWeight(.semibold)
                        Spacer()
                    }
                    .foregroundStyle(.black)
                    .padding(.vertical, 12)
                }
                .listRowBackground(BockColors.green)
            }

            if !playlists.isEmpty {
                Section("Suggested playlists") {
                    ForEach(playlists) { pl in
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
                }
            }

            if !albums.isEmpty {
                Section("Top albums") {
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
                                    BockIcon(icon: .playArrow, size: 24).foregroundStyle(BockColors.green)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }

            if !artists.isEmpty {
                Section("Top artists") {
                    ForEach(artists) { artist in
                        NavigationLink(value: SearchRoute.artist(artist.name)) {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(artist.name).foregroundStyle(BockColors.onSurface)
                                    Text("\(artist.tracks) tracks").font(.caption).foregroundStyle(BockColors.muted)
                                }
                                Spacer()
                                Button {
                                    appState.play(.artist(name: artist.name))
                                } label: {
                                    BockIcon(icon: .playArrow, size: 24).foregroundStyle(BockColors.green)
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
        .navigationTitle(genreName)
        .overlay {
            if loading {
                LoadingBox(logoSize: 48)
            }
        }
        .task(id: genreName) { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        async let albumsTask = appState.repository.albums(limit: 12, search: genreName)
        async let artistsTask = appState.repository.artists(limit: 12, search: genreName)
        async let historyTask = appState.repository.streamHistory(limit: 80)
        async let playlistsTask = appState.repository.playlists(limit: 200)

        albums = (try? await albumsTask)?.items ?? []
        artists = (try? await artistsTask)?.items ?? []

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
    }
}

struct ArtistDetailView: View {
    @ObservedObject var appState: AppState
    let artistName: String

    var body: some View {
        List {
            Button {
                appState.play(.artist(name: artistName))
            } label: {
                Label("Play artist", icon: .playArrow)
            }
            .foregroundStyle(BockColors.green)
        }
        .navigationTitle(artistName)
    }
}

struct AlbumDetailView: View {
    @ObservedObject var appState: AppState
    let albumName: String
    let artist: String?

    @State private var showMixMuse = false

    private var seed: DiscoverySeed {
        DiscoverySeed(kind: .album, title: albumName, album: albumName, artist: artist)
    }

    var body: some View {
        List {
            Button {
                appState.play(.album(name: albumName, artist: artist))
            } label: {
                Label("Play album", icon: .playArrow)
            }
            .foregroundStyle(BockColors.green)
            if let artist {
                Text(artist).foregroundStyle(BockColors.muted)
            }
        }
        .navigationTitle(albumName)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        showMixMuse = true
                    } label: { Label("Mix Muse playlist…", systemImage: "sparkles") }
                    Button { Task { await runResonanceRadio() } } label: {
                        Label("Resonance radio", systemImage: "waveform")
                    }
                    Button { Task { await runResonanceMix() } } label: {
                        Label("Resonance mix (save)", systemImage: "music.note.list")
                    }
                } label: {
                    BockIcon(icon: .moreVert, size: 22)
                }
            }
        }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: seed)
        }
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

    private func runResonanceMix() async {
        do {
            let resp = try await appState.repository.resonanceMix(
                seedKind: seed.kind.rawValue,
                album: seed.album, artist: seed.artist, save: true
            )
            if let pid = resp.playlistId ?? resp.id, !pid.isEmpty {
                appState.pendingPlayTarget = .playlist(id: pid, name: resp.name ?? "Resonance mix")
            }
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}
