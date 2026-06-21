import SwiftUI

struct DrivingModeView: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var local = LocalPlaybackController.shared

    var body: some View {
        VStack(spacing: 28) {
            Text("Driving Mode")
                .font(.largeTitle.bold())
            if let track = local.state.current {
                Text(track.title)
                    .font(.title2)
                    .multilineTextAlignment(.center)
                if let artist = track.artist {
                    Text(artist)
                        .foregroundStyle(BockColors.muted)
                }
            } else {
                Text("Nothing playing")
                    .foregroundStyle(BockColors.muted)
            }
            HStack(spacing: 36) {
                Button { local.skipPrevious() } label: {
                    BockIcon(icon: .skipPrevious, size: 44)
                }
                Button { local.togglePlayPause() } label: {
                    BockIcon(icon: local.state.isPlaying ? .pause : .playArrow, size: 56)
                }
                Button { local.skipNext() } label: {
                    BockIcon(icon: .skipNext, size: 44)
                }
            }
            .buttonStyle(.plain)
            Button("Open Now Playing") {
                appState.showNowPlayingSheet = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .bockBackground()
    }
}
