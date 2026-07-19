import SwiftUI

/// New-in-library browse — compact track/album list (not a full-screen genre hero).
struct ReleaseRadarView: View {
    @ObservedObject var appState: AppState

    @State private var albums: [LibraryNewAlbum] = []
    @State private var tracks: [LibraryNewTrack] = []
    @State private var heroArtURL: URL?
    @State private var loading = true

    var body: some View {
        ZStack {
            ArtBackdrop(url: heroArtURL)
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: "Release Radar")
                if loading && albums.isEmpty && tracks.isEmpty {
                    LoadingBox()
                        .frame(maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            header
                            if !albums.isEmpty {
                                PlexampSectionHeader(title: "NEW ALBUMS")
                                ForEach(Array(albums.enumerated()), id: \.offset) { _, album in
                                    NavigationLink(value: SearchRoute.album(name: album.album ?? "Album", artist: album.artist)) {
                                        PlexampAlbumRow(
                                            appState: appState,
                                            album: AlbumItem(
                                                album: album.album ?? "Album",
                                                artist: album.artist,
                                                track_count: 0,
                                                art_path: album.path
                                            )
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            if !tracks.isEmpty {
                                PlexampSectionHeader(title: "NEW TRACKS")
                                ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                                    if let path = track.path {
                                        BockTrackArtRow(
                                            appState: appState,
                                            title: track.title ?? path,
                                            subtitle: [track.artist, track.album].compactMap { $0 }.joined(separator: " · "),
                                            artPath: path,
                                            trackNumber: index + 1,
                                            onTap: {
                                                appState.play(.song(path: path, title: track.title ?? path))
                                            }
                                        )
                                    }
                                }
                            }
                            if albums.isEmpty && tracks.isEmpty {
                                Text("Nothing new this week")
                                    .foregroundStyle(.white.opacity(0.55))
                                    .padding(16)
                            }
                        }
                        .padding(.bottom, 24)
                    }
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task { await load() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            BockArtwork(url: heroArtURL, size: 88, cornerRadius: 6)
            VStack(alignment: .leading, spacing: 6) {
                Text("Added this week")
                    .font(.headline)
                    .foregroundStyle(.white)
                Text("\(albums.count) album\(albums.count == 1 ? "" : "s") · \(tracks.count) track\(tracks.count == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.65))
                Button {
                    appState.play(.radio(displayTitle: "New in library", seedKind: .genre, name: "Library", path: nil))
                } label: {
                    HStack(spacing: 6) {
                        BockIcon(icon: .playArrow, size: 16)
                        Text("Play all")
                            .fontWeight(.semibold)
                    }
                    .foregroundStyle(.black)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(BockColors.green)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func load() async {
        loading = true
        defer { loading = false }
        guard let data = try? await appState.repository.libraryNew() else { return }
        albums = data.albums
        tracks = data.tracks
        if let path = albums.first?.path ?? tracks.first?.path,
           let str = await appState.repository.artworkURL(for: path),
           let url = URL(string: str) {
            heroArtURL = url
        }
    }
}
