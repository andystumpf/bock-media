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
                BockIcon(icon: .libraryMusic, size: 72)
                    .foregroundStyle(BockColors.green)
                Text("Bock Media")
                    .font(.title.bold())
                    .foregroundStyle(BockColors.onSurface)
                ProgressView()
                    .tint(BockColors.green)
                    .scaleEffect(1.2)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(BockColors.muted)
            }
        }
    }
}
