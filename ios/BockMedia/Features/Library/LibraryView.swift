import SwiftUI

struct LibraryView: View {
    @ObservedObject var appState: AppState

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TabScreenHeader(title: "Your Library")
            List {
                navRow("Favorites", icon: .star, route: .favorites)
                navRow("Playlists", icon: .list, route: .playlists)
                navRow("Artists", icon: .mic, route: .artists)
                navRow("Albums", icon: .album, route: .albums)
                navRow("Songs", icon: .musicNote, route: .songs)
                navRow("Manage playlists", icon: .list, route: .managePlaylists)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func navRow(_ title: String, icon: BockIcons, route: LibraryRoute) -> some View {
        NavigationLink(value: route) {
            HStack(spacing: 12) {
                BockIcon(icon: icon, size: 24)
                    .foregroundStyle(BockColors.green)
                    .frame(width: 28)
                Text(title)
                    .foregroundStyle(BockColors.onSurface)
            }
        }
        .listRowBackground(BockColors.surfaceVariant.opacity(0.35))
    }
}
