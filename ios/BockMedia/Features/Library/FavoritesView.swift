import SwiftUI

struct FavoritesView: View {
    @ObservedObject var appState: AppState
    @State private var items: [FavoriteItem] = []
    @State private var loading = true

    var body: some View {
        Group {
            if loading {
                ProgressView().tint(BockColors.green)
            } else if items.isEmpty {
                ContentUnavailableView {
                    Label("No favorites", icon: .star, size: 40)
                } description: {
                    Text("Star tracks from Search to see them here.")
                }
            } else {
                List(items) { fav in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(fav.title ?? fav.path).foregroundStyle(BockColors.onSurface)
                            Text(fav.artist ?? "").font(.caption).foregroundStyle(BockColors.muted)
                        }
                        Spacer()
                        PlayDownloadActions(
                            appState: appState,
                            target: .song(path: fav.path, title: fav.title ?? fav.path),
                            compact: true
                        )
                    }
                    .listRowBackground(BockColors.surfaceVariant.opacity(0.4))
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .navigationTitle("Favorites")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        defer { loading = false }
        items = (try? await appState.repository.favorites()) ?? []
    }
}
