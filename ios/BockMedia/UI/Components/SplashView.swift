import SwiftUI

struct SplashView: View {
    var message: String = "Connecting…"

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [BockColors.homeGradientTop, BockColors.black],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack(spacing: 20) {
                BockLoadingLogo(size: 96)
                Text("Bock Media")
                    .font(.title.bold())
                    .foregroundStyle(BockColors.onSurface)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
            }
        }
    }
}
