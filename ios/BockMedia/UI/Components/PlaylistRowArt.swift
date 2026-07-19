import SwiftUI

/// Playlist thumbnail for list rows (Library, Playlists, Manage).
struct PlaylistRowArt: View {
    @ObservedObject var appState: AppState
    let playlistId: String
    let title: String
    var artPath: String?
    var size: CGFloat = 56
    var artEpoch: Int = 0

    @State private var url: URL?

    var body: some View {
        BockArtwork(url: url, size: size, cornerRadius: 6)
            .task(id: "\(playlistId)-\(artEpoch)") {
                await load()
            }
    }

    private func load() async {
        if let artPath, !artPath.isEmpty,
           let str = await appState.repository.artworkURL(for: artPath),
           let resolved = URL(string: str) {
            url = resolved
            return
        }
        if let path = HomeArtworkCache.playlistPath(id: playlistId),
           let str = await appState.repository.artworkURL(for: path),
           let resolved = URL(string: str) {
            url = resolved
            return
        }
        if let path = try? await appState.repository.playlistCoverPath(id: playlistId),
           let str = await appState.repository.artworkURL(for: path),
           let resolved = URL(string: str) {
            url = resolved
        }
    }
}
