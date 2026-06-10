import SwiftUI

struct PlaceholderAccountView: View {
    let title: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 12) {
            Text(title)
                .font(.title2.bold())
            Text("Coming in a later phase — mirrors Android \(title).")
                .multilineTextAlignment(.center)
                .foregroundStyle(BockColors.muted)
            Button("Done") { dismiss() }
                .padding(.top, 8)
        }
        .padding()
        .bockBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
