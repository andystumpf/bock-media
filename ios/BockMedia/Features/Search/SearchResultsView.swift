import SwiftUI

/// Shared grouped search results for Search tab and Library unified search.
struct SearchResultsView: View {
    @ObservedObject var appState: AppState
    let results: SearchResponse
    let query: String
    @Binding var resultFilter: SearchView.SearchResultFilter
    @Binding var expandedSections: Set<String>
    @Binding var expandedResults: [String: SearchResponse]

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }

    var body: some View {
        List {
            if resultFilter == .all, trimmedQuery.count >= 2 {
                if let top = results.songs.first {
                    Section("Top result") { hitRow(top, sectionKey: "songs") }
                } else if let top = results.artists.first {
                    Section("Top result") { hitRow(top, sectionKey: "artists") }
                } else if let top = results.albums.first {
                    Section("Top result") { hitRow(top, sectionKey: "albums") }
                } else if let top = results.playlists.first {
                    Section("Top result") { hitRow(top, sectionKey: "playlists") }
                }
            }
            if resultFilter == .all || resultFilter.sectionKey == "songs" {
                section("Tracks", key: "songs", hits: songHits)
            }
            if resultFilter == .all || resultFilter.sectionKey == "artists" {
                section("Artists", key: "artists", hits: artistHits)
            }
            if resultFilter == .all || resultFilter.sectionKey == "albums" {
                section("Albums", key: "albums", hits: albumHits)
            }
            if resultFilter == .all {
                section("Radio", key: "radios", hits: radioHits)
                section("Sonically similar", key: "similar", hits: similarHits)
            }
            if resultFilter == .all || resultFilter.sectionKey == "playlists" {
                section("Playlists", key: "playlists", hits: playlistHits)
            }
            if resultFilter == .all {
                section("Smart playlists", key: "smartPlaylists", hits: smartHits)
                section("Genres", key: "genres", hits: genreHits)
                section("Messages", key: "messages", hits: messageHits)
                section("Rooms", key: "rooms", hits: roomHits)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(BockTestTags.searchResults)
    }

    @ViewBuilder
    private func section(_ title: String, key: String, hits: [SearchHit]) -> some View {
        if !hits.isEmpty {
            Section(title) {
                ForEach(hits, id: \.displayId) { hit in
                    hitRow(hit, sectionKey: key)
                }
                if let total = results.counts[key], total > hits.count, !expandedSections.contains(key) {
                    Button("Show all \(total)") {
                        expandedSections.insert(key)
                        Task { await expandSection(key) }
                    }
                    .foregroundStyle(BockColors.green)
                }
            }
        }
    }

    @ViewBuilder
    private func hitRow(_ hit: SearchHit, sectionKey: String) -> some View {
        switch sectionKey {
        case "radios":
            let title = hit.name ?? hit.title ?? "Radio"
            Button { playRadioHit(hit) } label: {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(BockColors.surfaceVariant)
                            .frame(width: 56, height: 56)
                        Image(systemName: "waveform")
                            .font(.system(size: 22))
                            .foregroundStyle(BockColors.green)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(BockColors.onSurface)
                        Text("Radio")
                            .font(.caption)
                            .foregroundStyle(BockColors.muted)
                    }
                    Spacer()
                    BockIcon(icon: .playArrow, size: 22)
                        .foregroundStyle(BockColors.green)
                }
                .padding(.vertical, 4)
            }
            .buttonStyle(.plain)
        case "artists":
            if let name = hit.displayName {
                NavigationLink(value: SearchRoute.artist(name)) {
                    Text(name).foregroundStyle(BockColors.onSurface)
                }
            }
        case "albums":
            if let name = hit.displayName {
                NavigationLink(value: SearchRoute.album(name: name, artist: hit.artist)) {
                    VStack(alignment: .leading) {
                        Text(name).foregroundStyle(BockColors.onSurface)
                        if let artist = hit.artist {
                            Text(artist).font(.caption).foregroundStyle(BockColors.muted)
                        }
                    }
                }
            }
        case "playlists", "smartPlaylists":
            Button {
                if let id = hit.id, let name = hit.name {
                    appState.play(.playlist(id: id, name: name))
                }
            } label: {
                Text(hit.name ?? "Playlist").foregroundStyle(BockColors.onSurface)
            }
            .buttonStyle(.plain)
        case "genres":
            if let name = hit.name {
                NavigationLink(value: SearchRoute.genre(name)) {
                    Text(name).foregroundStyle(BockColors.onSurface)
                }
            }
        default:
            if let path = hit.path {
                Button {
                    appState.play(.song(path: path, title: hit.title ?? hit.name ?? "Track"))
                } label: {
                    VStack(alignment: .leading) {
                        Text(hit.title ?? hit.name ?? "Track").foregroundStyle(BockColors.onSurface)
                        if let artist = hit.artist {
                            Text(artist).font(.caption).foregroundStyle(BockColors.muted)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func playRadioHit(_ hit: SearchHit) {
        let title = hit.title ?? hit.name ?? "Radio"
        appState.play(.radio(displayTitle: title, seedKind: .artist, name: hit.name ?? title, path: hit.path))
    }

    private func expandSection(_ key: String) async {
        guard let response = try? await appState.repository.search(
            q: trimmedQuery,
            limit: 50,
            preview: 50,
            section: key,
            source: appState.preferences.effectiveSearchSource(),
            fast: false,
            includeResonance: key == "similar",
            includeRooms: false
        ) else { return }
        await MainActor.run { expandedResults[key] = response }
    }

    private var songHits: [SearchHit] {
        expandedSections.contains("songs") ? (expandedResults["songs"]?.songs ?? results.songs) : results.songs
    }
    private var artistHits: [SearchHit] {
        expandedSections.contains("artists") ? (expandedResults["artists"]?.artists ?? results.artists) : results.artists
    }
    private var albumHits: [SearchHit] {
        expandedSections.contains("albums") ? (expandedResults["albums"]?.albums ?? results.albums) : results.albums
    }
    private var playlistHits: [SearchHit] {
        expandedSections.contains("playlists") ? (expandedResults["playlists"]?.playlists ?? results.playlists) : results.playlists
    }
    private var smartHits: [SearchHit] {
        expandedSections.contains("smartPlaylists") ? (expandedResults["smartPlaylists"]?.smartPlaylists ?? results.smartPlaylists) : results.smartPlaylists
    }
    private var genreHits: [SearchHit] {
        expandedSections.contains("genres") ? (expandedResults["genres"]?.genres ?? results.genres) : results.genres
    }
    private var messageHits: [SearchHit] {
        expandedSections.contains("messages") ? (expandedResults["messages"]?.messages ?? results.messages) : results.messages
    }
    private var roomHits: [SearchHit] {
        expandedSections.contains("rooms") ? (expandedResults["rooms"]?.rooms ?? results.rooms) : results.rooms
    }
    private var radioHits: [SearchHit] {
        let src = expandedSections.contains("radios") ? (expandedResults["radios"] ?? results) : results
        return src.radios.map {
            SearchHit(name: $0.displayTitle ?? $0.name, title: $0.displayTitle, artist: $0.artist, path: $0.path)
        }
    }
    private var similarHits: [SearchHit] {
        expandedSections.contains("similar") ? (expandedResults["similar"]?.similar ?? results.similar) : results.similar
    }
}

struct SearchResultFilterChips: View {
    @Binding var selected: SearchView.SearchResultFilter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(SearchView.SearchResultFilter.allCases, id: \.self) { filter in
                    Button {
                        selected = filter
                    } label: {
                        Text(filter.rawValue)
                            .font(.subheadline.weight(selected == filter ? .bold : .medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(selected == filter ? BockColors.green : BockColors.pillInactive)
                            .foregroundStyle(selected == filter ? .black : BockColors.onSurface)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }
}

extension SearchView {
    enum SearchResultFilter: String, CaseIterable {
        case all = "All"
        case songs = "Songs"
        case artists = "Artists"
        case albums = "Albums"
        case playlists = "Playlists"

        var sectionKey: String? {
            switch self {
            case .all: return nil
            case .songs: return "songs"
            case .artists: return "artists"
            case .albums: return "albums"
            case .playlists: return "playlists"
            }
        }
    }
}
