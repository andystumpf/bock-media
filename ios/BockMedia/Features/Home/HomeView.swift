import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var feed: HomeFeed?
    @Published var loading = false
    @Published var error: String?
    @Published var filter: HomeFilter = .all
    @Published var offlineSection: HomeSection?

    func bootstrap(repository: BockMediaRepository) async {
        if let cached = HomeFeedCache.getIfFresh() {
            feed = cached
            loading = false
            HomeLoadCoordinator.markLoaded()
        } else if let snap = HomeCachePersistence.load() {
            HomeFeedCache.put(snap.feed)
            feed = snap.feed
            loading = false
            HomeLoadCoordinator.markLoaded()
        }
        if !HomeLoadCoordinator.shouldSkipReload() {
            await load(repository: repository)
        }
        await loadOffline()
    }

    func load(repository: BockMediaRepository) async {
        if feed == nil { loading = true }
        error = nil
        defer { loading = false }
        let fresh = await HomeFeedLoader.load(repository: repository)
        if !fresh.sections.isEmpty {
            HomeFeedCache.put(fresh)
            feed = fresh
            HomeLoadCoordinator.markLoaded()
            HomeCachePersistence.save(fresh)
        } else if feed == nil {
            let reachable: Bool
            if case .success = await repository.testConnection() { reachable = true } else { reachable = false }
            error = reachable
                ? "Could not load your library. Pull down to refresh."
                : "Can't reach your Bock Media server. Check connection in Settings."
        }
    }

    func loadOffline() async {
        offlineSection = HomeFeedLoader.offlineSection(store: OfflineDownloadStore())
    }

    var jumpBackInSection: HomeSection? {
        feed?.sections.first { $0.kind == .jumpBackIn }
    }

    /// Top shortcut tiles are quick-access only: playlists and mixes, never
    /// individual songs/albums (those come from play history). Recent playlists
    /// first, backfilled from mix rows. Mirrors Android `HomeFeed.homeShortcutCards()`.
    var shortcutCards: [HomeCard] {
        feed.map { $0.homeShortcutCards().filter { $0.eligibleForHomeShortcut } } ?? []
    }

    var showShortcuts: Bool {
        filter == .all && !shortcutCards.isEmpty
    }

    var filteredSections: [HomeSection] {
        if filter == .offline {
            return offlineSection.map { [$0] } ?? []
        }
        guard let feed else { return [] }
        return feed.sections.filter { section in
            switch filter {
            case .all:
                if showShortcuts && section.kind == .jumpBackIn { return false }
                return section.kind != .offline
            case .recents:
                return section.kind == .jumpBackIn || section.kind == .recentPlaylists
            case .playlists:
                return section.kind == .jumpBackIn || section.kind == .recentPlaylists || section.kind == .favorites
            case .mixes:
                return section.kind == .topMixes || section.kind == .exploreThemes || section.kind == .mood || section.kind == .dailyMixes
            case .radio:
                return section.kind == .radio
            case .discover:
                return section.kind == .discover
            case .offline:
                return section.kind == .offline
            }
        }
    }
}

struct HomeView: View {
    @ObservedObject var appState: AppState
    @Binding var accountRoute: AccountRoute?
    @StateObject private var viewModel = HomeViewModel()
    @State private var artworkEpoch = 0
    @State private var actionCard: HomeCard?
    @State private var showAllSection: HomeSection?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HomeHeaderView(filter: $viewModel.filter, accountRoute: $accountRoute)

                if !appState.remoteOk {
                    Text("Alexa remote unavailable — playing locally when possible.")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }

                if viewModel.loading && viewModel.feed == nil {
                    LoadingBox()
                        .padding(32)
                } else if let error = viewModel.error, viewModel.feed == nil {
                    VStack(spacing: 8) {
                        Text(error)
                            .foregroundStyle(.red)
                        Button("Retry") {
                            Task { await viewModel.load(repository: appState.repository) }
                        }
                    }
                    .padding(.horizontal, 16)
                } else if viewModel.filteredSections.isEmpty && !viewModel.showShortcuts {
                    emptyState
                } else {
                    if viewModel.showShortcuts {
                        HomeShortcutGrid(
                            appState: appState,
                            cards: viewModel.shortcutCards,
                            artworkEpoch: artworkEpoch,
                            onLongPress: { actionCard = $0 }
                        )
                    }
                    ForEach(viewModel.filteredSections) { section in
                        HomeSectionView(
                            appState: appState,
                            section: section,
                            artworkEpoch: artworkEpoch,
                            onShowAll: { showAllSection = $0 },
                            onLongPress: { actionCard = $0 }
                        )
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .accessibilityIdentifier(BockTestTags.homeFeed)
        .refreshable {
            await HomeLoadCoordinator.withLoadLock {
                await viewModel.load(repository: appState.repository)
                await viewModel.loadOffline()
            }
            warmArtwork()
        }
        .task {
            await viewModel.bootstrap(repository: appState.repository)
            warmArtwork()
            if !DeviceCatalog.isFresh() {
                Task { _ = await DeviceCatalog.refresh(repository: appState.repository, probe: false) }
            }
        }
        .onChange(of: viewModel.filter) { _, newValue in
            if newValue == .offline {
                Task { await viewModel.loadOffline() }
            }
        }
        .sheet(item: $actionCard) { card in
            HomeCardActionSheet(appState: appState, card: card) {
                actionCard = nil
            }
            .presentationDetents([.medium])
        }
        .sheet(item: $showAllSection) { section in
            HomeSectionShowAllSheet(appState: appState, section: section) {
                showAllSection = nil
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text(viewModel.filter == .offline ? "Nothing downloaded yet" : "Nothing here yet")
                .font(.headline)
                .foregroundStyle(BockColors.onSurface)
            Text(viewModel.filter == .offline
                 ? "Download playlists from Home tiles or long-press for more."
                 : "Pull down to refresh, or search for music to play.")
                .font(.subheadline)
                .foregroundStyle(BockColors.muted)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(32)
    }

    private func warmArtwork() {
        guard let feed = viewModel.feed else { return }
        let cards = feed.sections.flatMap(\.cards)
        artworkEpoch += 1
        Task {
            await HomeArtworkResolver.warmPlaylistCovers(
                repository: appState.repository,
                cards: cards
            )
            artworkEpoch += 1
        }
    }
}
