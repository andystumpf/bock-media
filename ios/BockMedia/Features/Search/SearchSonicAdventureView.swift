import SwiftUI

private struct SonicAdventureStop: Identifiable {
    let id = UUID()
    let title: String
    let subtitle: String
    let album: AlbumItem?
    let genre: GenreItem?
}

struct SearchSonicAdventureView: View {
    @ObservedObject var appState: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var loading = true
    @State private var stops: [SonicAdventureStop] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            PlexampInlineTopBar(title: "Sonic Adventure")
            Text("Pick a destination — albums and genres you may not have explored lately.")
                .font(.subheadline)
                .foregroundStyle(BockColors.muted)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            if loading {
                LoadingBox()
                    .frame(maxHeight: .infinity)
            } else if stops.isEmpty {
                Text("No adventure stops found yet")
                    .foregroundStyle(BockColors.muted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(stops.enumerated()), id: \.element.id) { index, stop in
                            sonicRow(index: index + 1, stop: stop)
                        }
                    }
                    .padding(.bottom, 24)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BockColors.black)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task { await loadStops() }
    }

    @ViewBuilder
    private func sonicRow(index: Int, stop: SonicAdventureStop) -> some View {
        if let album = stop.album {
            NavigationLink(value: SearchRoute.album(name: album.name, artist: album.artist)) {
                sonicRowLabel(index: index, stop: stop, artPath: album.art_path)
            }
            .simultaneousGesture(TapGesture().onEnded {
                SearchHistoryStore.addSelection(.fromAlbum(album.name, artist: album.artist))
            })
        } else if let genre = stop.genre {
            NavigationLink(value: SearchRoute.genre(genre.name)) {
                sonicRowLabel(index: index, stop: stop, artPath: genre.art_path)
            }
            .simultaneousGesture(TapGesture().onEnded {
                SearchHistoryStore.addSelection(.fromGenre(genre.name))
            })
        }
    }

    private func sonicRowLabel(index: Int, stop: SonicAdventureStop, artPath: String?) -> some View {
        HStack(spacing: 12) {
            Text("\(index)")
                .font(.caption.weight(.bold))
                .foregroundStyle(BockColors.muted)
                .frame(width: 20)
            BockArtwork(url: nil, size: 56, cornerRadius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(stop.title)
                    .foregroundStyle(BockColors.onSurface)
                Text(stop.subtitle)
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(BockColors.muted)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func loadStops() async {
        loading = true
        defer { loading = false }
        do {
            async let albumsTask = appState.repository.albums(page: 1, limit: 80, search: "")
            async let genresTask = appState.repository.genres(limit: 16)
            async let newTask = appState.repository.recentAlbums(limit: 12)
            let albums = try await albumsTask.items
            let genres = try await genresTask
            let newAlbums = try await newTask.items
            var picks: [SonicAdventureStop] = []
            for album in albums.filter({ $0.unplayed }).shuffled().prefix(3) {
                picks.append(SonicAdventureStop(
                    title: album.name,
                    subtitle: album.artist ?? "Unplayed album",
                    album: album,
                    genre: nil
                ))
            }
            for album in newAlbums.shuffled().prefix(2) {
                picks.append(SonicAdventureStop(
                    title: album.name,
                    subtitle: "New release · \(album.artist ?? "")",
                    album: album,
                    genre: nil
                ))
            }
            for genre in genres.shuffled().prefix(2) {
                picks.append(SonicAdventureStop(
                    title: genre.name,
                    subtitle: "Explore genre",
                    album: nil,
                    genre: genre
                ))
            }
            var seen = Set<String>()
            stops = picks.filter { seen.insert($0.title).inserted }.prefix(6).map { $0 }
        } catch {
            stops = []
        }
    }
}
