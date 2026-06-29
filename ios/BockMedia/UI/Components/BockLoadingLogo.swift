import SwiftUI

struct BockLoadingLogo: View {
    var size: CGFloat = 64
    var animating: Bool = true

    @State private var rotation: Double = 0

    private static let capPivot = UnitPoint(x: 0.5, y: 72.0 / 192.0)

    var body: some View {
        ZStack {
            Image("bock_logo_base")
                .resizable()
                .scaledToFit()

            Image("bock_logo_cap")
                .resizable()
                .scaledToFit()
                .rotationEffect(.degrees(animating ? rotation : 0), anchor: Self.capPivot)
        }
        .frame(width: size, height: size)
        .onAppear {
            guard animating else { return }
            rotation = 0
            withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                rotation = 360
            }
        }
        .onChange(of: animating) { _, isAnimating in
            if isAnimating {
                rotation = 0
                withAnimation(.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                    rotation = 360
                }
            } else {
                rotation = 0
            }
        }
    }
}

struct LoadingBox: View {
    var logoSize: CGFloat = 64

    var body: some View {
        BockLoadingLogo(size: logoSize)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
