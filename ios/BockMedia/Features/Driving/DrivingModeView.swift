import SwiftUI

struct DrivingModeView: View {
    @ObservedObject var appState: AppState
    @ObservedObject private var local = LocalPlaybackController.shared

    @State private var artURL: URL?

    var body: some View {
        let track = local.state.current
        let durationSec = max(local.state.durationMs / 1000, 0)
        let elapsedSec = max(local.state.positionMs / 1000, 0)
        let fraction = durationSec > 0 ? min(max(Double(elapsedSec) / Double(durationSec), 0), 1) : 0

        ZStack {
            DrivingArtBackdrop(url: artURL)

            VStack(spacing: 0) {
                Text("Driving")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.top, 8)

                Spacer(minLength: 12)

                if track != nil {
                    BockArtwork(url: artURL, size: min(UIScreen.main.bounds.width - 48, 320), cornerRadius: 14)
                        .shadow(color: .black.opacity(0.45), radius: 28, y: 14)
                } else {
                    RoundedRectangle(cornerRadius: 14)
                        .fill(.white.opacity(0.08))
                        .frame(width: min(UIScreen.main.bounds.width - 48, 320),
                               height: min(UIScreen.main.bounds.width - 48, 320))
                        .overlay {
                            Image(systemName: "opticaldisc")
                                .font(.system(size: 72))
                                .foregroundStyle(.white.opacity(0.22))
                        }
                }

                VStack(spacing: 6) {
                    Text(track?.title ?? "Nothing playing")
                        .font(.title2.bold())
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                    if let track {
                        Text([track.artist, track.album].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.title3)
                            .foregroundStyle(.white.opacity(0.65))
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                    } else {
                        Text("Start something from Home or Library")
                            .font(.body)
                            .foregroundStyle(BockColors.muted)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 28)

                Spacer(minLength: 16)

                if track != nil, durationSec > 0 {
                    VStack(spacing: 10) {
                        ProgressView(value: fraction)
                            .tint(.white)
                            .scaleEffect(x: 1, y: 2, anchor: .center)
                        HStack {
                            Text(formatPlaybackTime(seconds: elapsedSec))
                            Spacer()
                            Text(formatPlaybackTime(seconds: durationSec))
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white.opacity(0.6))
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 28)
                } else {
                    Spacer(minLength: 52)
                }

                HStack(spacing: 0) {
                    DrivingCircleButton(size: 76, enabled: track != nil) {
                        local.skipPrevious()
                    } label: {
                        BockIcon(icon: .skipPrevious, size: 40)
                    }
                    DrivingCircleButton(size: 56, enabled: track != nil) {
                        local.toggleShuffle()
                    } label: {
                        BockIcon(icon: .shuffle, size: 28)
                            .foregroundStyle(local.state.shuffle ? BockColors.green : .white)
                    }
                    DrivingPlayButton(isPlaying: local.state.isPlaying, enabled: track != nil) {
                        local.togglePlayPause()
                    }
                    DrivingCircleButton(size: 76, enabled: track != nil) {
                        local.skipNext()
                    } label: {
                        BockIcon(icon: .skipNext, size: 40)
                    }
                }
                .padding(.horizontal, 20)

                Button("Full player") {
                    appState.showNowPlayingSheet = true
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.65))
                .padding(.top, 16)
                .padding(.bottom, 12)
            }
        }
        .task(id: track?.path) {
            guard let path = track?.path, !path.isEmpty else {
                artURL = nil
                return
            }
            if let str = await appState.repository.artworkURL(for: path), let url = URL(string: str) {
                artURL = url
            } else {
                artURL = nil
            }
        }
    }
}

private struct DrivingArtBackdrop: View {
    let url: URL?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .scaleEffect(1.25)
                    .blur(radius: 56)
            } else {
                Color(white: 0.1)
            }
            LinearGradient(
                colors: [.black.opacity(0.92), .black.opacity(0.55), .black.opacity(0.42), .black.opacity(0.94)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .ignoresSafeArea()
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

private struct DrivingCircleButton<Label: View>: View {
    let size: CGFloat
    let enabled: Bool
    let action: () -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Button(action: action) {
            label()
                .frame(width: size, height: size)
                .background(Circle().fill(.white.opacity(enabled ? 0.14 : 0.06)))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

private struct DrivingPlayButton: View {
    let isPlaying: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BockIcon(icon: isPlaying ? .pause : .playArrow, size: 52)
                .foregroundStyle(enabled ? .black : .black.opacity(0.4))
                .frame(width: 96, height: 96)
                .background(Circle().fill(enabled ? .white : .white.opacity(0.28)))
                .shadow(color: .black.opacity(enabled ? 0.35 : 0), radius: 14, y: 6)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .padding(.horizontal, 20)
    }
}
