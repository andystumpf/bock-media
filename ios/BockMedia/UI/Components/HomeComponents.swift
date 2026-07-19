import SwiftUI

enum HomeGreeting {
    static func text(profileFirstName: String? = nil) -> String {
        let hour = Calendar.current.component(.hour, from: Date())
        let period: String
        switch hour {
        case 0...11: period = "Good morning"
        case 12...16: period = "Good afternoon"
        default: period = "Good evening"
        }
        let first = profileFirstName?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
            .first
            .map(String.init)
            .flatMap { $0.isEmpty ? nil : $0 }
        if let first { return "\(period) \(first)" }
        return period
    }
}

struct HomeHeaderView: View {
    @Binding var filter: HomeFilter
    @Binding var accountRoute: AccountRoute?
    var profileFirstName: String? = nil
    var onOpenListenAgent: () -> Void = {}

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text(HomeGreeting.text(profileFirstName: profileFirstName))
                    .font(.title2.bold())
                    .foregroundStyle(BockColors.onSurface)
                    .accessibilityIdentifier(BockTestTags.homeGreeting)
                Spacer()
                ListenAgentMicButton(onTap: onOpenListenAgent)
                AccountMenuButton(route: $accountRoute)
            }
            HomeFilterPills(filter: $filter)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .homeHeaderBackground()
    }
}

struct HomeFilterPills: View {
    @Binding var filter: HomeFilter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(HomeFilter.allCases) { f in
                    Button { filter = f } label: {
                        Text(f.label)
                            .font(.subheadline.weight(filter == f ? .bold : .medium))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(filter == f ? BockColors.pillActive : BockColors.pillInactive)
                            .foregroundStyle(filter == f ? BockColors.onPrimary : BockColors.onSurface)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

struct HomeCardArtwork: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var artworkEpoch: Int
    var size: CGFloat
    var cornerRadius: CGFloat = 4

    @State private var url: URL?

    var body: some View {
        BockArtwork(url: url, size: size, cornerRadius: cornerRadius)
            .task(id: "\(card.id)-\(artworkEpoch)") {
                await load()
            }
    }

    private func load() async {
        if let playlistId = card.linkedPlaylistId {
            // Inline cover from /api/playlists (first track) — paint without a per-tile lookup.
            if let path = HomeArtworkCache.playlistPath(id: playlistId) ?? card.artPath,
               let str = await appState.repository.artworkURL(for: path),
               let cached = URL(string: str) {
                url = cached
                await ArtworkImageCache.prefetch(cached)
                return
            }
            if let path = try? await appState.repository.playlistCoverPath(id: playlistId),
               let str = await appState.repository.artworkURL(for: path),
               let resolved = URL(string: str) {
                url = resolved
                await ArtworkImageCache.prefetch(resolved)
                return
            }
        }
        if let cached = HomeArtworkCache.url(for: card.id) {
            url = cached
            await ArtworkImageCache.prefetch(cached)
            return
        }
        guard let resolved = await HomeArtworkResolver.resolveURL(
            repository: appState.repository,
            card: card
        ) else { return }
        url = resolved
        await ArtworkImageCache.prefetch(resolved)
    }
}

struct HomeShortcutGrid: View {
    @ObservedObject var appState: AppState
    let cards: [HomeCard]
    var artworkEpoch: Int
    var onLongPress: ((HomeCard) -> Void)?

    private let columns = [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 8) {
            ForEach(cards.prefix(6)) { card in
                HomeShortcutTile(
                    appState: appState,
                    card: card,
                    artworkEpoch: artworkEpoch,
                    onLongPress: { onLongPress?(card) }
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

struct HomeShortcutTile: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var artworkEpoch: Int
    var onLongPress: (() -> Void)?

    var body: some View {
        Button { appState.playHomeCard(card) } label: {
            HStack(spacing: 0) {
                HomeCardArtwork(
                    appState: appState,
                    card: card,
                    artworkEpoch: artworkEpoch,
                    size: 56,
                    cornerRadius: 4
                )
                Text(card.title)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .padding(.horizontal, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(height: 56)
            .background(BockColors.elevatedSurface)
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            LongPressGesture().onEnded { _ in onLongPress?() }
        )
    }
}

struct HomeSectionView: View {
    @ObservedObject var appState: AppState
    let section: HomeSection
    var artworkEpoch: Int
    var onShowAll: ((HomeSection) -> Void)?
    var onLongPress: ((HomeCard) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(section.title)
                    .font(.title2.bold())
                    .foregroundStyle(BockColors.onSurface)
                Spacer()
                if section.cards.count > 4, onShowAll != nil {
                    Button { onShowAll?(section) } label: {
                        HStack(spacing: 2) {
                            Text("Show all")
                                .font(.subheadline.weight(.bold))
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.bold))
                        }
                        .foregroundStyle(BockColors.muted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 16) {
                    ForEach(section.cards) { card in
                        HomeCollectionTile(
                            appState: appState,
                            card: card,
                            section: section,
                            artworkEpoch: artworkEpoch,
                            onLongPress: { onLongPress?(card) }
                        )
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.top, 8)
    }
}

struct HomeCollectionTile: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    let section: HomeSection
    var artworkEpoch: Int
    var onLongPress: (() -> Void)?
    private let size: CGFloat = 148

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                tileArtworkLink
                if card.showsHomeDownloadOverlay(sectionKind: section.kind, sectionId: section.id) {
                    HomeTileDownloadStatus(appState: appState, target: card.playTarget)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        .padding(4)
                }
                if card.showsHomePlayOverlay(sectionKind: section.kind, sectionId: section.id) {
                    HomeTileOverlayPlay {
                        appState.playHomeCard(card)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                }
            }
            .frame(width: size, height: size)
            tileLabelLink
        }
        .fixedSize(horizontal: true, vertical: false)
        .simultaneousGesture(LongPressGesture().onEnded { _ in onLongPress?() })
    }

    @ViewBuilder
    private var tileArtworkLink: some View {
        browseNavigationLink { tileArtwork }
    }

    @ViewBuilder
    private var tileLabelLink: some View {
        if card.browseDestination?.libraryRoute != nil || card.browseDestination?.searchRoute != nil {
            browseNavigationLink { tileLabels }
        } else {
            Button { appState.playHomeCard(card) } label: { tileLabels }.buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func browseNavigationLink<Label: View>(@ViewBuilder label: () -> Label) -> some View {
        if let dest = card.browseDestination, let route = dest.libraryRoute {
            NavigationLink(value: route, label: label).buttonStyle(.plain)
        } else if let dest = card.browseDestination, let route = dest.searchRoute {
            NavigationLink(value: route, label: label).buttonStyle(.plain)
        } else {
            label()
        }
    }

    private var tileArtwork: some View {
        HomeCardArtwork(
            appState: appState,
            card: card,
            artworkEpoch: artworkEpoch,
            size: size,
            cornerRadius: 8
        )
    }

    private var tileLabels: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(card.title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(BockColors.onSurface)
                .lineLimit(2)
                .frame(width: size, alignment: .leading)
            if let sub = card.subtitle {
                Text(sub)
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
                    .lineLimit(1)
                    .frame(width: size, alignment: .leading)
            }
        }
    }
}

struct HomeCardActionSheet: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var manager = OfflineDownloadManager.shared
    let card: HomeCard
    var onDismiss: () -> Void

    private var downloadState: DownloadState? {
        manager.status(for: card.playTarget)?.state
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(card.title)
                .font(.title2.bold())
                .foregroundStyle(BockColors.onSurface)
            if let sub = card.subtitle {
                Text(sub)
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
            }
            Button {
                onDismiss()
                appState.playHomeCard(card)
            } label: {
                HStack {
                    BockIcon(icon: .playArrow, size: 24)
                    Text("Play")
                        .fontWeight(.bold)
                }
                .foregroundStyle(BockColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color.white.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .buttonStyle(.plain)
            Button {
                onDismiss()
                OfflineDownloadManager.shared.download(
                    repository: appState.repository,
                    preferences: appState.preferences,
                    target: card.playTarget
                )
            } label: {
                HStack {
                    BockIcon(icon: .download, size: 24)
                    Text("Download for offline")
                        .fontWeight(.bold)
                }
                .foregroundStyle(BockColors.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color.white.opacity(0.1))
                .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .buttonStyle(.plain)
            .disabled(downloadState == .downloading || downloadState == .idle)
            .opacity(downloadState == .downloading || downloadState == .idle ? 0.45 : 1)
            if downloadState == .downloading {
                Text("Download in progress…")
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
            } else if downloadState == .idle {
                Text("Queued for download…")
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
            }
        }
        .padding(20)
        .presentationBackground(BockColors.sheetBg)
    }
}

/// Offline download state overlay for home collection tiles — mirrors Android `DownloadStatusControl`.
struct HomeTileDownloadStatus: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var manager = OfflineDownloadManager.shared
    let target: PlayTarget

    @Environment(\.visibleDownloadStatuses) private var visibleDownloadStatuses

    private var status: OfflineCollectionStatus? {
        let id = target.downloadId()
        if !visibleDownloadStatuses.isEmpty {
            return visibleDownloadStatuses[id]
        }
        return manager.status(for: target)
    }

    var body: some View {
        Group {
            switch status?.state {
            case .downloading:
                ZStack {
                    Circle().fill(Color.black.opacity(0.5))
                    ProgressView(value: Double(status?.progress ?? 0))
                        .progressViewStyle(.circular)
                        .tint(BockColors.green)
                        .scaleEffect(0.65)
                }
                .frame(width: 32, height: 32)
            case .idle:
                ZStack {
                    Circle().fill(Color.black.opacity(0.5))
                    Circle()
                        .stroke(BockColors.green.opacity(0.55), lineWidth: 2)
                        .frame(width: 18, height: 18)
                }
                .frame(width: 32, height: 32)
            case .complete:
                Button {
                    manager.deleteCollection(target.downloadId())
                } label: {
                    ZStack {
                        Circle().fill(Color.black.opacity(0.5))
                        BockIcon(icon: .downloadDone, size: 20)
                            .foregroundStyle(BockColors.green)
                    }
                    .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            default:
                Button {
                    manager.download(
                        repository: appState.repository,
                        preferences: appState.preferences,
                        target: target
                    )
                } label: {
                    ZStack {
                        Circle().fill(Color.black.opacity(0.5))
                        BockIcon(icon: .download, size: 20)
                            .foregroundStyle(.white)
                    }
                    .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct HomeSectionShowAllSheet: View {
    @ObservedObject var appState: AppState
    let section: HomeSection
    var onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            List(section.cards) { card in
                if let dest = card.browseDestination, let route = dest.libraryRoute {
                    NavigationLink(value: route) {
                        showAllRow(card)
                    }
                } else if let dest = card.browseDestination, let route = dest.searchRoute {
                    NavigationLink(value: route) {
                        showAllRow(card)
                    }
                } else {
                    Button {
                        onDismiss()
                        appState.playHomeCard(card)
                    } label: {
                        showAllRow(card)
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(BockColors.sheetBg)
            .navigationTitle(section.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onDismiss)
                        .foregroundStyle(BockColors.onSurface)
                }
            }
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .playlistDetail(let id):
                    PlaylistDetailView(appState: appState, playlistId: id)
                case .albums(let artist):
                    AlbumsView(appState: appState, artist: artist)
                case .songs(_, let album):
                    SongsView(appState: appState, artist: nil, album: album)
                default:
                    EmptyView()
                }
            }
            .navigationDestination(for: SearchRoute.self) { route in
                switch route {
                case .genre(let name):
                    GenreDetailView(appState: appState, genreName: name)
                case .artist(let name):
                    ArtistDetailView(appState: appState, artistName: name)
                case .album(let name, let artist):
                    AlbumDetailView(appState: appState, albumName: name, artist: artist)
                case .releaseRadar:
                    ReleaseRadarView(appState: appState)
                case .sonicAdventure:
                    SearchSonicAdventureView(appState: appState)
                }
            }
        }
        .presentationBackground(BockColors.sheetBg)
    }

    private func showAllRow(_ card: HomeCard) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(card.title)
                .font(.body.weight(.bold))
                .foregroundStyle(BockColors.onSurface)
            if let sub = card.subtitle {
                Text(sub)
                    .font(.caption)
                    .foregroundStyle(BockColors.muted)
            }
        }
    }
}
