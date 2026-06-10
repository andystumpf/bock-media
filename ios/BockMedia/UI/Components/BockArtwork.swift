import SwiftUI

struct BockArtwork: View {
    let url: URL?
    var size: CGFloat = 120
    var cornerRadius: CGFloat = 8

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                artPlaceholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            if let cached = ArtworkImageCache.memoryImage(for: url) {
                image = cached
                return
            }
            image = await ArtworkImageCache.load(url)
        }
    }

    private var artPlaceholder: some View {
        LinearGradient(
            colors: [BockColors.navy, BockColors.surfaceVariant],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}
