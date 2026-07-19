import AVKit
import SwiftUI
import UIKit

@MainActor
func buildMusicVideoPlayer(url: URL, preferences: AppPreferences) -> AVPlayer {
    let item = StreamAuth.playerItem(url: url, preferences: preferences)
    let player = AVPlayer(playerItem: item)
    player.isMuted = true
    player.automaticallyWaitsToMinimizeStalling = false
    if !OfflineDownloadNetwork.shared.isOnWifi {
        item.preferredPeakBitRate = 600_000
    }
    player.play()
    player.pause()
    return player
}

/// Full-bleed muted stream via AVPlayerLayer (no AVPlayerViewController safe-area side effects).
private struct MutedLoopingVideoPlayer: UIViewRepresentable {
    let url: URL
    let preferences: AppPreferences
    let playing: Bool
    let positionMs: Int64
    var preloadedPlayer: AVPlayer? = nil
    @Binding var isRendered: Bool

    func makeCoordinator() -> Coordinator { Coordinator(isRendered: $isRendered) }

    func makeUIView(context: Context) -> PlayerFillView {
        let view = PlayerFillView()
        view.backgroundColor = .clear
        view.playerLayer.videoGravity = .resizeAspectFill
        let player = preloadedPlayer ?? buildMusicVideoPlayer(url: url, preferences: preferences)
        view.playerLayer.player = player
        context.coordinator.player = player
        context.coordinator.ownsPlayer = preloadedPlayer == nil
        if let item = player.currentItem {
            context.coordinator.observeLoop(player: player, item: item)
            context.coordinator.observeReady(item: item)
        }
        sync(player: player, coordinator: context.coordinator, playing: playing, positionMs: positionMs)
        return view
    }

    func updateUIView(_ uiView: PlayerFillView, context: Context) {
        guard let player = context.coordinator.player else { return }
        sync(player: player, coordinator: context.coordinator, playing: playing, positionMs: positionMs)
    }

    private func sync(player: AVPlayer, coordinator: Coordinator, playing: Bool, positionMs: Int64) {
        coordinator.playing = playing
        let target = CMTime(seconds: Double(max(0, positionMs)) / 1000.0, preferredTimescale: 1000)
        let currentSec = CMTimeGetSeconds(player.currentTime())
        let targetSec = CMTimeGetSeconds(target)
        if abs(currentSec - targetSec) > 0.2 {
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        }
        if playing {
            player.play()
        } else {
            player.pause()
        }
    }

    static func dismantleUIView(_ uiView: PlayerFillView, coordinator: Coordinator) {
        coordinator.endObserver.map { NotificationCenter.default.removeObserver($0) }
        coordinator.statusObserver?.invalidate()
        if coordinator.ownsPlayer {
            uiView.playerLayer.player?.pause()
            uiView.playerLayer.player = nil
        }
    }

    final class PlayerFillView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    final class Coordinator {
        var endObserver: NSObjectProtocol?
        var statusObserver: NSKeyValueObservation?
        weak var player: AVPlayer?
        var playing = true
        var ownsPlayer = true
        @Binding var isRendered: Bool

        init(isRendered: Binding<Bool>) {
            _isRendered = isRendered
        }

        func observeLoop(player: AVPlayer, item: AVPlayerItem) {
            endObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { [weak self] _ in
                player.seek(to: .zero)
                if self?.playing == true { player.play() }
            }
        }

        func observeReady(item: AVPlayerItem) {
            statusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
                guard item.status == .readyToPlay else { return }
                DispatchQueue.main.async {
                    self?.isRendered = true
                }
            }
        }
    }
}

/// Muted server stream — Bock audio stays on the library stream (matches Android `MusicVideoPlayer`).
struct MusicVideoPlayerView: View {
    let playUrl: URL?
    let videoId: String
    let artURL: URL?
    let loading: Bool
    let preferences: AppPreferences
    var playing: Bool = true
    var positionMs: Int64 = 0
    var preloadedPlayer: AVPlayer? = nil

    @State private var isRendered = false

    private var fallbackThumbURL: URL? { URL(string: "https://i.ytimg.com/vi/\(videoId)/hqdefault.jpg") }

    var body: some View {
        ZStack {
            if let artURL {
                AsyncImage(url: artURL) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        Color(white: 0.1)
                    }
                }
            } else if let fallbackThumbURL {
                AsyncImage(url: fallbackThumbURL) { phase in
                    if case .success(let image) = phase {
                        image.resizable().scaledToFill()
                    } else {
                        Color(white: 0.1)
                    }
                }
            }

            if let playUrl {
                MutedLoopingVideoPlayer(
                    url: playUrl,
                    preferences: preferences,
                    playing: playing,
                    positionMs: positionMs,
                    preloadedPlayer: preloadedPlayer,
                    isRendered: $isRendered
                )
                .opacity(isRendered ? 1 : 0)
                .id(playUrl.absoluteString)
            }

            if loading && !isRendered {
                BockProgressIndicator(size: 32)
                    .tint(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .ignoresSafeArea()
        .onChange(of: playUrl?.absoluteString) { _, _ in
            isRendered = false
        }
    }
}

struct NowPlayingOverlayIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var selected: Bool = false
    var loading: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(selected ? Color.white.opacity(0.18) : Color.black.opacity(0.55))
                    .overlay(
                        Circle()
                            .strokeBorder(Color.white.opacity(selected ? 0.5 : 0.35), lineWidth: 1)
                    )
                if loading {
                    BockProgressIndicator(size: 18)
                        .tint(.white)
                } else {
                    Image(systemName: systemImage)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
        .disabled(!enabled || loading)
        .accessibilityLabel(accessibilityLabel)
    }
}

struct VideoModeToggleButton: View {
    let showingVideo: Bool
    let loading: Bool
    let action: () -> Void

    var body: some View {
        NowPlayingOverlayIconButton(
            systemImage: showingVideo ? "photo.on.rectangle.angled" : "video",
            accessibilityLabel: loading ? "Finding video" : (showingVideo ? "Switch to cover" : "Switch to video"),
            selected: showingVideo,
            loading: loading,
            action: action
        )
        .accessibilityIdentifier("nowPlayingVideoToggle")
    }
}

struct LyricsModeToggleButton: View {
    let showingLyrics: Bool
    let action: () -> Void

    var body: some View {
        NowPlayingOverlayIconButton(
            systemImage: showingLyrics ? "photo.on.rectangle.angled" : "text.quote",
            accessibilityLabel: showingLyrics ? "Show cover art" : "Show lyrics",
            selected: showingLyrics,
            action: action
        )
    }
}

struct NowPlayingBottomFade: View {
    var deep: Bool = false

    var body: some View {
        GeometryReader { proxy in
            LinearGradient(
                stops: deep ? [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.08), location: 0.35),
                    .init(color: .black.opacity(0.28), location: 0.55),
                    .init(color: .black.opacity(0.52), location: 0.72),
                    .init(color: .black.opacity(0.72), location: 0.88),
                    .init(color: .black.opacity(0.82), location: 1),
                ] : [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.22), location: 0.22),
                    .init(color: .black.opacity(0.48), location: 0.48),
                    .init(color: .black.opacity(0.85), location: 0.72),
                    .init(color: .black.opacity(0.96), location: 0.9),
                    .init(color: .black, location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: proxy.size.height * (deep ? 0.55 : 0.56), alignment: .bottom)
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .allowsHitTesting(false)
    }
}
