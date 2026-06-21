import SwiftUI

struct ArtistsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [ArtistItem] = []
    @State private var loading = true

    var body: some View {
        browseList(loading: loading, empty: "No artists") {
            ForEach(items) { artist in
                NavigationLink(value: LibraryRoute.albums(artist: artist.name)) {
                    HStack {
                        Text(artist.name).foregroundStyle(BockColors.onSurface)
                        Spacer()
                        PlayDownloadActions(appState: appState, target: .artist(name: artist.name), compact: true)
                    }
                }
                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
            }
        }
        .navigationTitle("Artists")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.artists())?.items ?? []
    }
}

struct AlbumsView: View {
    @ObservedObject var appState: AppState
    var artist: String? = nil
    @State private var items: [AlbumItem] = []
    @State private var page = 1
    @State private var total = 0
    @State private var loading = true
    @State private var loadingMore = false
    @State private var showMixMuse = false
    @State private var mixMuseSeed: DiscoverySeed?

    var body: some View {
        browseList(loading: loading && items.isEmpty, empty: "No albums") {
            ForEach(items) { album in
                NavigationLink(value: LibraryRoute.songs(artist: album.artist, album: album.name)) {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(album.name).foregroundStyle(BockColors.onSurface)
                            Text(album.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                        PlayDownloadActions(
                            appState: appState,
                            target: .album(name: album.name, artist: album.artist),
                            compact: true
                        )
                    }
                }
                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                .contextMenu {
                    DiscoveryContextMenuItems(
                        appState: appState,
                        seed: DiscoverySeed(kind: .album, title: album.name, album: album.name, artist: album.artist),
                        showMixMuse: $showMixMuse,
                        mixMuseSeed: $mixMuseSeed
                    )
                }
            }
            if loadingMore {
                HStack { Spacer(); ProgressView(); Spacer() }
                    .listRowBackground(Color.clear)
            } else if items.count < total {
                Color.clear
                    .frame(height: 1)
                    .onAppear { Task { await loadMore() } }
                    .listRowBackground(Color.clear)
            }
        }
        .navigationTitle(artist.map { "Albums · \($0)" } ?? "Albums")
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: mixMuseSeed)
        }
    }

    private func reload() async {
        page = 1
        loading = true
        defer { loading = false }
        if let response = try? await appState.repository.albums(page: 1, limit: 50, search: "", artist: artist) {
            items = response.items
            total = response.total
        } else {
            items = []
            total = 0
        }
    }

    private func loadMore() async {
        guard !loadingMore, items.count < total else { return }
        loadingMore = true
        defer { loadingMore = false }
        let next = page + 1
        if let response = try? await appState.repository.albums(page: next, limit: 50, search: "", artist: artist) {
            items.append(contentsOf: response.items)
            page = next
            total = response.total
        }
    }
}

struct SongsView: View {
    @ObservedObject var appState: AppState
    var artist: String? = nil
    var album: String? = nil
    @State private var items: [SongItem] = []
    @State private var page = 1
    @State private var total = 0
    @State private var loading = true
    @State private var loadingMore = false
    @State private var showMixMuse = false
    @State private var mixMuseSeed: DiscoverySeed?

    var body: some View {
        browseList(loading: loading && items.isEmpty, empty: "No songs") {
            ForEach(items) { song in
                HStack {
                    VStack(alignment: .leading) {
                        Text(song.title ?? "Song").foregroundStyle(BockColors.onSurface)
                        Text(song.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    if let path = song.path {
                        PlayDownloadActions(
                            appState: appState,
                            target: .song(path: path, title: song.title ?? path),
                            compact: true
                        )
                    }
                }
                .contextMenu {
                    if let path = song.path {
                        DiscoveryContextMenuItems(
                            appState: appState,
                            seed: DiscoverySeed(
                                kind: .song,
                                title: song.title ?? path,
                                path: path,
                                artist: song.artist,
                                album: song.album
                            ),
                            showMixMuse: $showMixMuse,
                            mixMuseSeed: $mixMuseSeed
                        )
                    }
                }
                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
            }
            if loadingMore {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else if items.count < total {
                Color.clear.frame(height: 1).onAppear { Task { await loadMore() } }
            }
        }
        .navigationTitle(album ?? artist ?? "Songs")
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(isPresented: $showMixMuse) {
            MixMusePromptSheet(appState: appState, seed: mixMuseSeed)
        }
    }

    private func reload() async {
        page = 1
        loading = true
        defer { loading = false }
        if let response = try? await appState.repository.songs(page: 1, limit: 100, artist: artist, album: album) {
            items = response.items
            total = response.total
        } else {
            items = []
            total = 0
        }
    }

    private func loadMore() async {
        guard !loadingMore, items.count < total else { return }
        loadingMore = true
        defer { loadingMore = false }
        let next = page + 1
        if let response = try? await appState.repository.songs(page: next, limit: 100, artist: artist, album: album) {
            items.append(contentsOf: response.items)
            page = next
            total = response.total
        }
    }
}

@ViewBuilder
private func browseList<Content: View>(
    loading: Bool,
    empty: String,
    @ViewBuilder content: () -> Content
) -> some View {
    if loading {
        LoadingBox()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else {
        List { content() }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
    }
}
