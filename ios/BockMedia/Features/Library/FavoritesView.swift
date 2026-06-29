import SwiftUI

struct FavoritesView: View {
    @ObservedObject var appState: AppState
    @State private var items: [FavoriteItem] = []
    @State private var loading = true
    @State private var refreshing = false

    var body: some View {
        Group {
            if loading && items.isEmpty {
                LoadingBox()
            } else if items.isEmpty {
                VStack(spacing: 16) {
                    Text("Like tracks in Now Playing or Search.")
                        .foregroundStyle(BockColors.muted)
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List(items) { fav in
                    favoriteRow(fav)
                        .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Favorites")
        .task { await load() }
        .refreshable {
            refreshing = true
            await load()
        }
    }

    private func favoriteRow(_ fav: FavoriteItem) -> some View {
        HStack(spacing: 12) {
            FavoriteArtwork(path: fav.path, repository: appState.repository, title: fav.title ?? fav.path)
            VStack(alignment: .leading, spacing: 2) {
                Text(fav.title ?? fav.path)
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(1)
                Text([fav.artist, fav.album].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                    .lineLimit(1)
            }
            Spacer()
            Button {
                Task {
                    try? await appState.repository.removeFavorite(path: fav.path)
                    await load()
                }
            } label: {
                BockIcon(icon: .star, size: 22)
                    .foregroundStyle(BockColors.green)
            }
            .buttonStyle(.plain)
            PlayDownloadActions(
                appState: appState,
                target: .song(path: fav.path, title: fav.title ?? fav.path),
                compact: true
            )
        }
    }

    private func load() async {
        if items.isEmpty { loading = true }
        defer {
            loading = false
            refreshing = false
        }
        items = (try? await appState.repository.favorites()) ?? []
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
                if let urlStr = await repository.artworkURL(for: path), let u = URL(string: urlStr) {
                    url = u
                }
            }
    }
}
