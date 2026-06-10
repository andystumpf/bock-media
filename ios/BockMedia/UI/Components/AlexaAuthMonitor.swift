import SwiftUI

struct AlexaAuthMonitor: View {
    @ObservedObject var appState: AppState
    @State private var lastAuth: Bool?
    @State private var showBanner = false

    var body: some View {
        Group {
            if showBanner {
                HStack {
                    Text("Alexa session expired — re-login in Settings")
                        .font(.caption)
                    Spacer()
                    Button("Dismiss") { showBanner = false }
                        .font(.caption.weight(.semibold))
                }
                .foregroundStyle(BockColors.onSurface)
                .padding(10)
                .background(BockColors.gold.opacity(0.25))
            }
        }
        .task {
            while !Task.isCancelled {
                if let status = try? await appState.repository.alexaRemoteStatus() {
                    if status.configured && status.authenticated == false && lastAuth != false {
                        showBanner = true
                    }
                    lastAuth = status.authenticated
                    appState.remoteOk = alexaControlsAvailable(status)
                }
                try? await Task.sleep(nanoseconds: 120_000_000_000)
            }
        }
    }
}
