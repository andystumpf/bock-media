import SwiftUI

@MainActor
final class HomeViewModel: ObservableObject {
    @Published var feed: HomeFeed?
    @Published var loading = false
    @Published var error: String?
    @Published var filter: HomeFilter = .all

    func load(repository: BockMediaRepository) async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            feed = await HomeFeedLoader.load(repository: repository)
        } catch {
            self.error = error.localizedDescription
        }
    }

    var jumpBackInSection: HomeSection? {
        feed?.sections.first { $0.kind == .jumpBackIn }
    }

    var showShortcuts: Bool {
        filter == .all && jumpBackInSection != nil
    }

    var filteredSections: [HomeSection] {
        guard let feed else { return [] }
        if filter == .offline {
            if let offline = HomeFeedLoader.offlineSection(store: OfflineDownloadStore()) {
                return [offline]
            }
            return []
        }
        return feed.sections.filter { section in
            switch filter {
            case .all:
                if showShortcuts && section.kind == .jumpBackIn { return false }
                return section.kind != .offline
            case .playlists:
                return section.kind == .jumpBackIn || section.kind == .recentPlaylists || section.kind == .favorites
            case .mixes:
                return section.kind == .topMixes || section.kind == .dailyMixes
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

                if viewModel.loading && viewModel.feed == nil {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                        .padding(32)
                } else if let error = viewModel.error {
                    Text(error)
                        .foregroundStyle(.red)
                        .padding(.horizontal, 16)
                } else if viewModel.filteredSections.isEmpty && !viewModel.showShortcuts {
                    emptyState
                } else {
                    if viewModel.showShortcuts, let jump = viewModel.jumpBackInSection {
                        HomeShortcutGrid(
                            appState: appState,
                            cards: jump.cards,
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
        .refreshable {
            await viewModel.load(repository: appState.repository)
            warmArtwork()
        }
        .task {
            await viewModel.load(repository: appState.repository)
            warmArtwork()
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
