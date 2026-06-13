import SwiftUI

struct HomeCardRow: View {
    @ObservedObject var appState: AppState
    let card: HomeCard
    var artworkEpoch: Int
    var width: CGFloat = 124
    var artworkSize: CGFloat = 124

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomTrailing) {
                Button { appState.play(card.playTarget) } label: {
                    HomeCardArtwork(
                        appState: appState,
                        card: card,
                        artworkEpoch: artworkEpoch,
                        size: artworkSize,
                        cornerRadius: 8
                    )
                }
                .buttonStyle(.plain)
                PlayDownloadActions(appState: appState, target: card.playTarget, compact: true)
                    .padding(6)
                    .background(.black.opacity(0.45))
                    .clipShape(Capsule())
                    .padding(4)
            }
            .frame(width: artworkSize, height: artworkSize)
            Button { appState.play(card.playTarget) } label: {
                Text(card.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(BockColors.onSurface)
                    .lineLimit(2)
                    .frame(width: width, alignment: .leading)
                if let sub = card.subtitle {
                    Text(sub)
                        .font(.caption)
                        .foregroundStyle(BockColors.muted)
                        .lineLimit(1)
                        .frame(width: width, alignment: .leading)
                }
            }
            .buttonStyle(.plain)
        }
        .frame(width: width, alignment: .leading)
        .fixedSize(horizontal: true, vertical: false)
    }
}
