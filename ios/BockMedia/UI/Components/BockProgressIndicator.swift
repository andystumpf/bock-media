import SwiftUI

/// Green circular spinner — matches Android `BockProgressIndicator` / `LoadingBox`.
struct BockProgressIndicator: View {
    var size: CGFloat = 40

    var body: some View {
        ProgressView()
            .progressViewStyle(.circular)
            .tint(BockColors.green)
            .frame(width: size, height: size)
    }
}

/// Full-screen loading state with test tag — matches Android `LoadingBox`.
struct LoadingBox: View {
    var size: CGFloat = 40

    var body: some View {
        BockProgressIndicator(size: size)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityIdentifier(BockTestTags.screenLoading)
    }
}
