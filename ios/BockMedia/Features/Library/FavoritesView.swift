import SwiftUI

struct FavoritesView: View {
    @ObservedObject var appState: AppState
    @State private var items: [RatingItem] = []
    @State private var loading = true
    @State private var refreshing = false

    private var byStar: [Int: [RatingItem]] {
        Dictionary(grouping: items.filter { $0.kind == "song" && (1...5).contains($0.stars) }, by: \.stars)
    }

    var body: some View {
        Group {
            if loading && items.isEmpty {
                LoadingBox()
            } else if byStar.isEmpty {
                VStack(spacing: 16) {
                    Text("Rate tracks in Now Playing or Search.")
                        .foregroundStyle(BockColors.muted)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    Text("Play all tracks at each rating level.")
                        .font(.subheadline)
                        .foregroundStyle(BockColors.muted)
                        .listRowBackground(Color.clear)
                    ForEach(RatedSongPlaylists.starLevelsDescending, id: \.self) { stars in
                        if let songs = byStar[stars], !songs.isEmpty {
                            ratedRow(stars: stars, songs: songs)
                                .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .accessibilityIdentifier(BockTestTags.favoritesBody)
        .navigationTitle("Rated Songs")
        .task(id: appState.profileChangeRevision) { await load() }
        .refreshable {
            refreshing = true
            await load()
        }
    }

    private func ratedRow(stars: Int, songs: [RatingItem]) -> some View {
        NavigationLink(value: LibraryRoute.playlistDetail(RatedSongPlaylists.id(stars))) {
            HStack(spacing: 12) {
                FavoriteArtwork(path: songs.first?.id ?? "", repository: appState.repository, title: RatedSongPlaylists.title(stars))
                VStack(alignment: .leading, spacing: 2) {
                    Text(RatedSongPlaylists.title(stars))
                        .foregroundStyle(BockColors.onSurface)
                    Text("\(songs.count) tracks · Tap to browse")
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                }
                Spacer()
            }
        }
    }

    private func load() async {
        if items.isEmpty { loading = true }
        defer {
            loading = false
            refreshing = false
        }
        items = (try? await appState.repository.ratedSongs()) ?? []
    }
}

private struct FavoriteArtwork: View {
    let path: String
    let repository: BockMediaRepository
    let title: String
    @State private var url: URL?

    var body: some View {
        BockArtwork(url: url, size: 56, cornerRadius: 6)
            .task(id: path) {
                guard !path.isEmpty else { return }
                if let urlStr = await repository.artworkURL(for: path), let u = URL(string: urlStr) {
                    url = u
                }
            }
    }
}
