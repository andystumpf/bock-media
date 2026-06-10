import SwiftUI

enum HomeGreeting {
    static var text: String {
        let hour = Calendar.current.component(.hour, from: Date())
        switch hour {
        case 0...11: return "Good morning"
        case 12...16: return "Good afternoon"
        default: return "Good evening"
        }
    }
}

struct HomeHeaderView: View {
    @Binding var filter: HomeFilter
    @Binding var accountRoute: AccountRoute?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                Text(HomeGreeting.text)
                    .font(.title2.bold())
                    .foregroundStyle(BockColors.onSurface)
                Spacer()
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
                        if section.kind == .topMixes || section.kind == .dailyMixes {
                            HomeGenreMixTile(
                                appState: appState,
                                card: card,
                                artworkEpoch: artworkEpoch,
                                onLongPress: { onLongPress?(card) }
                            )
                        } else {
                            HomePlaylistTile(
                                appState: appState,
                                card: card,
                                artworkEpoch: artworkEpoch,
                                onLongPress: { onLongPress?(card) }
                            )
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.top, 8)
    }
}

struct HomePlaylistTile: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var artworkEpoch: Int
    var onLongPress: (() -> Void)?
    private let size: CGFloat = 148

    var body: some View {
        Button { appState.playHomeCard(card) } label: {
            VStack(alignment: .leading, spacing: 8) {
                HomeCardArtwork(
                    appState: appState,
                    card: card,
                    artworkEpoch: artworkEpoch,
                    size: size,
                    cornerRadius: 4
                )
                Text(card.title)
                    .font(.subheadline.weight(.bold))
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
        .buttonStyle(.plain)
        .fixedSize(horizontal: true, vertical: false)
        .simultaneousGesture(LongPressGesture().onEnded { _ in onLongPress?() })
    }
}

struct HomeGenreMixTile: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var artworkEpoch: Int
    var onLongPress: (() -> Void)?
    private let size: CGFloat = 148

    var body: some View {
        Button { appState.playHomeCard(card) } label: {
            ZStack(alignment: .bottomLeading) {
                HomeCardArtwork(
                    appState: appState,
                    card: card,
                    artworkEpoch: artworkEpoch,
                    size: size,
                    cornerRadius: 4
                )
                LinearGradient(
                    colors: [
                        .clear,
                        BockColors.mixAccent(for: card.id).opacity(0.25),
                        .black.opacity(0.72),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.title)
                        .font(.headline.weight(.bold))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    if let sub = card.subtitle {
                        Text(sub)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.85))
                            .lineLimit(1)
                    }
                }
                .padding(12)
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
        .fixedSize(horizontal: true, vertical: false)
        .simultaneousGesture(LongPressGesture().onEnded { _ in onLongPress?() })
    }
}

struct HomeCardActionSheet: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var onDismiss: () -> Void

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
        }
        .padding(20)
        .presentationBackground(BockColors.sheetBg)
    }
}

struct HomeSectionShowAllSheet: View {
    @ObservedObject var appState: AppState
    let section: HomeSection
    var onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            List(section.cards) { card in
                Button {
                    onDismiss()
                    appState.playHomeCard(card)
                } label: {
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
        }
        .presentationBackground(BockColors.sheetBg)
    }
}
