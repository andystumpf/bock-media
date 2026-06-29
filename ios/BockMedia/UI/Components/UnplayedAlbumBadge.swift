import SwiftUI

/// Plex-style corner marker for albums you have not played yet.
struct UnplayedAlbumBadge: View {
    var body: some View {
        Circle()
            .strokeBorder(BockColors.green, lineWidth: 2.5)
            .background(Circle().fill(Color.black.opacity(0.35)))
            .frame(width: 14, height: 14)
            .shadow(color: .black.opacity(0.35), radius: 2, x: 0, y: 1)
    }
}

struct ArtworkWithUnplayedBadge<Content: View>: View {
    var showUnplayed: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        ZStack(alignment: .topLeading) {
            content()
            if showUnplayed {
                UnplayedAlbumBadge()
                    .padding(6)
            }
        }
    }
}
