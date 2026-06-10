import SwiftUI

struct ArtistsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [ArtistItem] = []
    @State private var loading = true

    var body: some View {
        browseList(loading: loading, empty: "No artists") {
            ForEach(items) { artist in
                HStack {
                    Text(artist.name).foregroundStyle(BockColors.onSurface)
                    Spacer()
                    PlayDownloadActions(appState: appState, target: .artist(name: artist.name), compact: true)
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
    @State private var items: [AlbumItem] = []
    @State private var loading = true

    var body: some View {
        browseList(loading: loading, empty: "No albums") {
            ForEach(items) { album in
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
                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
            }
        }
        .navigationTitle("Albums")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.albums())?.items ?? []
    }
}

struct SongsView: View {
    @ObservedObject var appState: AppState
    @State private var items: [SongItem] = []
    @State private var loading = true

    var body: some View {
        browseList(loading: loading, empty: "No songs") {
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
                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
            }
        }
        .navigationTitle("Songs")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.songs(limit: 200))?.items ?? []
    }
}

@ViewBuilder
private func browseList<Content: View>(
    loading: Bool,
    empty: String,
    @ViewBuilder content: () -> Content
) -> some View {
    if loading {
        ProgressView().tint(BockColors.green)
    } else {
        List { content() }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
    }
}
