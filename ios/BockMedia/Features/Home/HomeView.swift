import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var feed: HomeFeed?
    @Published var loading = false
    @Published var error: String?
    @Published var filter: HomeFilter = .all
    @Published var offlineSection: HomeSection?

    func bootstrap(repository: BockMediaRepository) async {
        let profileLinked = ActiveProfileStore.activeMemberId() != nil
        if let cached = HomeFeedCache.getIfFresh(),
           cached.isUsableHomeCache(
               activeProfileLinked: profileLinked,
               hasRatedSongs: HomeFeedCache.peekHasRatedSongs()
           ) {
            feed = cached
            loading = false
            HomeLoadCoordinator.markLoaded()
        } else if let snap = HomeCachePersistence.load(),
                  snap.feed.isUsableHomeCache(
                      activeProfileLinked: profileLinked,
                      hasRatedSongs: snap.hasRatedSongs
                  ) {
            HomeArtworkCache.restore(playlistPaths: snap.playlistPaths)
            HomeFeedCache.put(snap.feed, hasRatedSongs: snap.hasRatedSongs)
            feed = snap.feed
            loading = false
            HomeLoadCoordinator.markLoaded()
        }

        let needsReload = !HomeLoadCoordinator.shouldSkipReload()
        if feed != nil {
            await loadOffline()
            if needsReload {
                Task {
                    await HomeLoadCoordinator.withLoadLock {
                        await load(repository: repository)
                        await loadOffline()
                    }
                }
            }
            return
        }
        if needsReload {
            loading = true
            Task {
                await HomeLoadCoordinator.withLoadLock {
                    await load(repository: repository)
                    await loadOffline()
                }
            }
        } else {
            await loadOffline()
        }
    }

    func load(repository: BockMediaRepository, forcePaint: Bool = false) async {
        if feed == nil { loading = true }
        error = nil
        defer { loading = false }
        let fresh = await HomeFeedLoader.load(repository: repository)
        if !fresh.sections.isEmpty {
            let hadVisibleFeed = feed != nil && !forcePaint
            HomeFeedCache.put(fresh)
            HomeLoadCoordinator.markLoaded()
            let withinSkipWindow = HomeLoadCoordinator.shouldSkipReload()
            if HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed: hadVisibleFeed, withinSkipReloadWindow: withinSkipWindow) {
                feed = fresh
                HomeCachePersistence.save(fresh)
                await FollowNotificationSync.checkAndNotify(repository: repository)
            } else {
                HomeCachePersistence.save(fresh)
            }
        } else if feed == nil {
            let reachable: Bool
            if case .success = await repository.testConnection() { reachable = true } else { reachable = false }
            error = reachable
                ? "Could not load your library. Pull down to refresh."
                : "Can't reach your Bock Media server. Check connection in Settings."
        }
    }

    func loadOffline() async {
        offlineSection = await Task.detached(priority: .utility) {
            HomeFeedLoader.offlineSection(store: OfflineDownloadStore())
        }.value
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
                return section.kind == .jumpBackIn || section.kind == .recentPlaylists || section.kind == .recentlyCreated
            case .playlists:
                return section.kind == .jumpBackIn || section.kind == .recentPlaylists || section.kind == .recentlyCreated
                    || section.kind == .favorites || section.kind == .ratedSongs || section.kind == .decade
                    || section.kind == .browseGenres
            case .mixes:
                return section.kind == .topMixes || section.kind == .browseGenres
                    || section.kind == .exploreThemes || section.kind == .mood || section.kind == .dailyMixes
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
    var onOpenListenAgent: () -> Void = {}
    @StateObject private var viewModel = HomeViewModel()
    @State private var artworkEpoch = 0
    @State private var actionCard: HomeCard?
    @State private var showAllSection: HomeSection?
    @State private var profileFirstName: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HomeHeaderView(
                    filter: $viewModel.filter,
                    accountRoute: $accountRoute,
                    profileFirstName: profileFirstName,
                    onOpenListenAgent: onOpenListenAgent
                )

                if !appState.remoteOk {
                    Text("Alexa remote unavailable — playing locally when possible.")
                        .font(.caption)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                }

                if appState.activeMemberId == nil {
                    Text("Select your profile in Family to restore ratings and settings.")
                        .font(.caption)
                        .foregroundStyle(BockColors.green)
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
        .contentMargins(.horizontal, 0, for: .scrollContent)
        .toolbar(.hidden, for: .navigationBar)
        .accessibilityIdentifier(BockTestTags.homeFeed)
        .refreshable {
            await HomeLoadCoordinator.withLoadLock {
                await viewModel.load(repository: appState.repository, forcePaint: true)
                await viewModel.loadOffline()
            }
            warmArtwork()
        }
        .task {
            await reloadProfileFirstName()
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
        .onChange(of: appState.profileChangeRevision) { _, _ in
            Task { await reloadProfileFirstName() }
            OfflineDownloadSync.claimOrphansForActiveProfile()
        }
        .onChange(of: appState.activeMemberId) { oldValue, newValue in
            guard HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId: oldValue, currentMemberId: newValue) else { return }
            Task { await reloadProfileFirstName() }
            Task {
                await viewModel.loadOffline()
                await HomeLoadCoordinator.withLoadLock {
                    await viewModel.load(repository: appState.repository, forcePaint: true)
                }
                warmArtwork()
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

    private func reloadProfileFirstName() async {
        guard let id = appState.activeMemberId?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !id.isEmpty
        else {
            profileFirstName = nil
            return
        }
        let members = (try? await appState.repository.household())?.members ?? []
        let name = members.first(where: { $0.id == id })?.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
            .first
            .map(String.init)
        profileFirstName = name.flatMap { $0.isEmpty ? nil : $0 }
    }

    private func warmArtwork() {
        guard let feed = viewModel.feed else { return }
        let cards = feed.sections.prefix(4).flatMap { $0.cards.prefix(12) }
        guard !cards.isEmpty else { return }
        artworkEpoch += 1
        Task {
            await HomeArtworkResolver.warmPlaylistCovers(
                repository: appState.repository,
                cards: Array(cards)
            )
            artworkEpoch += 1
        }
    }
}
