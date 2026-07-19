import SwiftUI

/// Plexamp-style blurred album-art background with dark gradient overlay (Android `ArtBackdrop`).
struct ArtBackdrop: View {
    let url: URL?
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Group {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                            .frame(width: proxy.size.width, height: proxy.size.height)
                            .scaleEffect(1.25)
                            .blur(radius: 56)
                    } else {
                        Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x1A / 255)
                    }
                }
                .clipped()
                LinearGradient(
                    stops: [
                        .init(color: .black.opacity(0.92), location: 0),
                        .init(color: .black.opacity(0.55), location: 0.2),
                        .init(color: .black.opacity(0.42), location: 0.55),
                        .init(color: .black.opacity(0.94), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
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
}
