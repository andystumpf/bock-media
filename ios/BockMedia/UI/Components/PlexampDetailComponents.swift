import SwiftUI
import UIKit

// MARK: - Track rows with artwork

struct BockTrackArtRow: View {
    @ObservedObject var appState: AppState
    let title: String
    var subtitle: String?
    var artPath: String?
    var trackNumber: Int?
    var durationSeconds: Int?
    let onTap: () -> Void

    @State private var artURL: URL?

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                BockArtwork(url: artURL, size: 48, cornerRadius: 4)
                if let trackNumber {
                    Text("\(trackNumber)")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.45))
                        .frame(width: 20, alignment: .trailing)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.body)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.55))
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if let durationSeconds {
                    Text(PlexampFormat.trackDuration(durationSeconds))
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.45))
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
        .task(id: artPath) {
            guard let artPath,
                  let str = await appState.repository.artworkURL(for: artPath),
                  let url = URL(string: str) else {
                artURL = nil
                return
            }
            artURL = url
        }
    }
}

struct PlexampPlaylistTrackRow: View {
    @ObservedObject var appState: AppState
    let track: PlaylistTrack
    let onTap: () -> Void
    var onMenu: (() -> Void)?

    var body: some View {
        HStack(spacing: 0) {
            BockTrackArtRow(
                appState: appState,
                title: track.title ?? "Track",
                subtitle: track.artist,
                artPath: track.path,
                durationSeconds: track.duration,
                onTap: onTap
            )
            if let onMenu {
                Button(action: onMenu) {
                    BockIcon(icon: .moreVert, size: 20)
                        .foregroundStyle(.white.opacity(0.65))
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .padding(.trailing, 8)
            }
        }
    }
}

struct PlexampPlaylistHero: View {
    @ObservedObject var appState: AppState
    let playlistId: String
    let name: String
    let tracks: [PlaylistTrack]
    let artURL: URL?
    let onPlay: () -> Void
    let onShuffle: () -> Void
    let onMenu: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            playlistArt
            VStack(alignment: .leading, spacing: 0) {
                Text(name)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer().frame(height: 14)
                DetailHeroPlayButton(action: onPlay)
                HStack(spacing: 0) {
                    DetailHeroIconButton(icon: .shuffle, action: onShuffle)
                    DetailHeroIconButton(icon: .moreVert, action: onMenu)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private var playlistArt: some View {
        if let artURL {
            BockArtwork(url: artURL, size: 120, cornerRadius: 6)
        } else if let path = tracks.compactMap(\.path).first {
            PlaylistArtwork(appState: appState, path: path, playlistId: playlistId)
        } else {
            BockArtwork(url: nil, size: 120, cornerRadius: 6)
        }
    }
}

private struct PlaylistArtwork: View {
    @ObservedObject var appState: AppState
    let path: String
    let playlistId: String
    @State private var url: URL?

    var body: some View {
        BockArtwork(url: url, size: 120, cornerRadius: 6)
            .task(id: "\(playlistId)-\(path)") {
                if let cached = try? await appState.repository.playlistCoverPath(id: playlistId),
                   let str = await appState.repository.artworkURL(for: cached),
                   let u = URL(string: str) {
                    url = u
                    return
                }
                if let str = await appState.repository.artworkURL(for: path), let u = URL(string: str) {
                    url = u
                }
            }
    }
}

// MARK: - Top bar

struct PlexampInlineTopBar: View {
    let title: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: {
                BockIcon(icon: .arrowBack, size: 24)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Back")
            Text(title)
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .padding(.top, 4)
    }
}

// MARK: - Hero actions

struct DetailHeroPlayButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BockIcon(icon: .playArrow, size: 28)
                .foregroundStyle(.white)
                .frame(width: 52, height: 52)
                .background(Color.white.opacity(0.18))
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

struct DetailHeroIconButton: View {
    let icon: BockIcons
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.system(size: 22))
                } else {
                    BockIcon(icon: icon, size: 22)
                }
            }
            .foregroundStyle(.white)
            .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Section headers & rows

struct PlexampSectionHeader: View {
    let title: String
    var subtitle: String?
    var trailing: String?

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.bold))
                    .tracking(0.5)
                    .foregroundStyle(.white)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.55))
                }
            }
            Spacer()
            if let trailing {
                Text(trailing)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.white.opacity(0.55))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct PlexampAlbumRow: View {
    @ObservedObject var appState: AppState
    let album: AlbumItem
    @State private var url: URL?

    var body: some View {
        HStack(spacing: 12) {
            BockArtwork(url: url, size: 56, cornerRadius: 4)
            VStack(alignment: .leading, spacing: 2) {
                Text(album.name)
                    .font(.body)
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text(albumSubtitle)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .task(id: album.id) {
            if let path = album.artPath,
               let str = await appState.repository.artworkURL(for: path) {
                url = URL(string: str)
            }
        }
    }

    private var albumSubtitle: String {
        [
            album.year.map(String.init),
            album.track_count > 0 ? "\(album.track_count) tracks" : nil,
        ]
        .compactMap { $0 }
        .joined(separator: " · ")
    }
}

struct PlexampTrackRow: View {
    let title: String
    var subtitle: String?
    var trackNumber: Int?
    var isHot: Bool = false
    var durationSeconds: Int?
    let onTap: () -> Void
    var onMenu: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    if let trackNumber {
                        Text("\(trackNumber)")
                            .font(.subheadline)
                            .foregroundStyle(.white.opacity(0.45))
                            .frame(width: 28, alignment: .trailing)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 4) {
                            Text(title)
                                .font(.body)
                                .foregroundStyle(.white)
                                .lineLimit(1)
                            if isHot {
                                Image(systemName: "flame.fill")
                                    .font(.caption)
                                    .foregroundStyle(BockColors.gold)
                            }
                        }
                        if let subtitle {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.55))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                    if let durationSeconds, durationSeconds > 0 {
                        Text(PlexampFormat.trackDuration(durationSeconds))
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.45))
                    }
                }
            }
            .buttonStyle(.plain)
            if let onMenu {
                Button(action: onMenu) {
                    BockIcon(icon: .moreVert, size: 20)
                        .foregroundStyle(.white.opacity(0.65))
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: - Artist accent gradient

private let accentGradientPalette: [(Color, Color)] = [
    (Color(red: 0.27, green: 0.04, blue: 0.04), Color(red: 0.50, green: 0.11, blue: 0.11)),
    (Color(red: 0.26, green: 0.08, blue: 0.03), Color(red: 0.60, green: 0.20, blue: 0.07)),
    (Color(red: 0.26, green: 0.13, blue: 0.02), Color(red: 0.52, green: 0.30, blue: 0.05)),
    (Color(red: 0.08, green: 0.33, blue: 0.18), Color(red: 0.09, green: 0.40, blue: 0.20)),
    (Color(red: 0.02, green: 0.31, blue: 0.24), Color(red: 0.02, green: 0.47, blue: 0.34)),
    (Color(red: 0.09, green: 0.31, blue: 0.39), Color(red: 0.05, green: 0.46, blue: 0.56)),
    (Color(red: 0.12, green: 0.23, blue: 0.54), Color(red: 0.11, green: 0.31, blue: 0.85)),
    (Color(red: 0.19, green: 0.18, blue: 0.54), Color(red: 0.26, green: 0.22, blue: 0.80)),
    (Color(red: 0.35, green: 0.11, blue: 0.53), Color(red: 0.49, green: 0.13, blue: 0.81)),
    (Color(red: 0.44, green: 0.10, blue: 0.46), Color(red: 0.64, green: 0.11, blue: 0.69)),
    (Color(red: 0.51, green: 0.10, blue: 0.26), Color(red: 0.75, green: 0.09, blue: 0.36)),
    (Color(red: 0.12, green: 0.16, blue: 0.22), Color(red: 0.22, green: 0.25, blue: 0.32)),
]

func gradientAccentColor(for seed: String) -> Color {
    let pair = accentGradientPalette[abs(seed.hashValue) % accentGradientPalette.count]
    return pair.0
}

func heroGradientColors(accent: Color) -> [Color] {
    [accent, Color(red: 0.07, green: 0.07, blue: 0.07), .black]
}

// MARK: - Spotify-style artist detail

struct SpotifyArtistHeroBanner: View {
    let artistName: String
    let artURL: URL?
    var accentColor: Color = Color(red: 0.1, green: 0.1, blue: 0.18)
    let onMore: () -> Void
    var onListenAgent: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            LinearGradient(
                colors: heroGradientColors(accent: accentColor),
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(maxWidth: .infinity)
            .frame(height: 300)

            BockArtwork(url: artURL, size: 168, cornerRadius: 84)
                .offset(y: -16)

            LinearGradient(
                colors: [.black.opacity(0.35), .clear, .black.opacity(0.95)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 300)

            HStack {
                Button { dismiss() } label: {
                    BockIcon(icon: .arrowBack, size: 24)
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                Spacer()
                if let onListenAgent {
                    ListenAgentMicButton(onTap: onListenAgent)
                }
                Button(action: onMore) {
                    BockIcon(icon: .moreVert, size: 24)
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.horizontal, 8)
            .padding(.top, 4)

            Text(artistName)
                .font(.title.weight(.bold))
                .foregroundStyle(.white)
                .lineLimit(2)
                .padding(.horizontal, 16)
                .padding(.bottom, 20)
        }
    }
}

struct SpotifyArtistActions: View {
    var statLine: String?
    var albumCount: Int = 0
    var followed = false
    let onPlay: () -> Void
    let onShuffle: () -> Void
    let onRadio: () -> Void
    var onFollowToggle: (() -> Void)? = nil

    private var displayStatLine: String? {
        if let statLine, !statLine.isEmpty { return statLine }
        if albumCount > 0 { return "\(albumCount) albums in library" }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let displayStatLine {
                Text(displayStatLine)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.65))
            }
            HStack {
                Button(action: onShuffle) {
                    BockIcon(icon: .shuffle, size: 22)
                        .foregroundStyle(.white.opacity(0.85))
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                Button(action: onRadio) {
                    Image(systemName: "waveform")
                        .font(.system(size: 22))
                        .foregroundStyle(.white.opacity(0.85))
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                if let onFollowToggle {
                    Button(action: onFollowToggle) {
                        Text(followed ? "Following" : "Follow")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(followed ? BockColors.green : .white.opacity(0.85))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(followed ? BockColors.green.opacity(0.25) : Color.white.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
                Button(action: onPlay) {
                    BockIcon(icon: .playArrow, size: 32)
                        .foregroundStyle(.black)
                        .frame(width: 56, height: 56)
                        .background(BockColors.green)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct SpotifySectionTab: View {
    let label: String
    var selected = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.title3.weight(.bold))
                .foregroundStyle(selected ? .white : .white.opacity(0.55))
            if selected {
                Rectangle()
                    .fill(BockColors.green)
                    .frame(width: 48, height: 2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

struct SpotifySectionTitle: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct ArtistLibraryStatsStrip: View {
    let trackCount: Int
    let albumCount: Int
    let totalPlays: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if trackCount > 0 {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.caption)
                        .foregroundStyle(BockColors.green)
                    Text("Verified library")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(BockColors.green)
                }
            }
            let parts = libraryStatParts
            if !parts.isEmpty {
                Text(parts.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.65))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }

    private var libraryStatParts: [String] {
        var parts: [String] = []
        if totalPlays > 0 { parts.append("\(ArtistDetailRules.formatLibraryCount(totalPlays)) plays") }
        if trackCount > 0 { parts.append("\(trackCount) tracks") }
        if albumCount > 0 { parts.append("\(albumCount) albums") }
        return parts
    }
}

struct ArtistSectionTabs: View {
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        HStack(spacing: 24) {
            tabButton(id: "music", label: "Music")
            tabButton(id: "about", label: "About")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    private func tabButton(id: String, label: String) -> some View {
        Button { onSelect(id) } label: {
            VStack(alignment: .leading, spacing: 8) {
                Text(label)
                    .font(.title3.weight(.bold))
                    .foregroundStyle(selected == id ? .white : .white.opacity(0.55))
                if selected == id {
                    Rectangle()
                        .fill(BockColors.green)
                        .frame(width: 48, height: 2)
                }
            }
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

struct CollapsibleSectionHeader: View {
    let title: String
    var subtitle: String?
    let expanded: Bool
    let onToggle: () -> Void
    var onPlay: (() -> Void)? = nil
    var playAccessibilityLabel: String = "Play all"

    var body: some View {
        HStack(alignment: .center) {
            Button(action: onToggle) {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.title3.weight(.bold))
                            .foregroundStyle(.white)
                        if let subtitle {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.55))
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            if let onPlay {
                Button(action: onPlay) {
                    BockIcon(icon: .playArrow, size: 20)
                        .foregroundStyle(BockColors.green)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playAccessibilityLabel)
            }
            Button(action: onToggle) {
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .foregroundStyle(.white.opacity(0.55))
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

struct ArtistLatestAlbumCard: View {
    @ObservedObject var appState: AppState
    let album: AlbumItem
    let artistName: String
    let onTap: () -> Void
    @State private var artURL: URL?

    var body: some View {
        HStack(spacing: 12) {
            BockArtwork(url: artURL, size: 72, cornerRadius: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text("Latest in library")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(BockColors.green)
                Text(album.name)
                    .font(.body.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Text([album.year.map(String.init), artistName].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.white.opacity(0.55))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .task(id: album.artPath) {
            guard let path = album.artPath,
                  let str = await appState.repository.artworkURL(for: path),
                  let url = URL(string: str) else {
                artURL = nil
                return
            }
            artURL = url
        }
    }
}

struct ArtistAboutSection: View {
    let about: ArtistDetailAbout?
    let artistName: String
    let trackCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("About \(artistName)")
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
            if let firstAdded = about?.firstAdded?.trimmingCharacters(in: .whitespacesAndNewlines), !firstAdded.isEmpty {
                aboutFactRow(label: "First added to library", value: firstAdded)
            }
            if let decade = about?.topDecade, decade > 0 {
                aboutFactRow(label: "Most played decade", value: "\(decade)s")
            }
            if trackCount > 0 {
                aboutFactRow(label: "Tracks in library", value: "\(trackCount)")
            }
            if let genres = about?.topGenres, !genres.isEmpty {
                Text("Top genres")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.85))
                Text(genres.joined(separator: ", "))
                    .font(.body)
                    .foregroundStyle(.white.opacity(0.65))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func aboutFactRow(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.55))
            Spacer()
            Text(value)
                .font(.body)
                .foregroundStyle(.white)
        }
        .padding(.vertical, 6)
    }
}

struct ArtistSimilarArtistRow: View {
    @ObservedObject var appState: AppState
    let name: String
    var onTap: (() -> Void)? = nil
    @State private var artURL: URL?

    var body: some View {
        Group {
            if let onTap {
                Button(action: onTap) { rowContent }
                    .buttonStyle(.plain)
            } else {
                rowContent
            }
        }
        .task(id: name) {
            if let path = try? await appState.repository.artistPortraitPath(for: name),
               let str = await appState.repository.artworkURL(for: path),
               let url = URL(string: str) {
                artURL = url
                return
            }
            if let path = try? await appState.repository.songs(page: 1, limit: 1, search: name, artist: name).items.first?.path,
               let str = await appState.repository.artworkURL(for: path),
               let url = URL(string: str) {
                artURL = url
            }
        }
    }

    private var rowContent: some View {
        HStack(spacing: 12) {
            BockArtwork(url: artURL, size: 48, cornerRadius: 24)
            Text(name)
                .font(.body)
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .foregroundStyle(.white.opacity(0.55))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

struct TrackLikeButton: View {
    let liked: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Image(systemName: liked ? "heart.fill" : "heart")
                .font(.body)
                .foregroundStyle(liked ? BockColors.green : .white.opacity(0.55))
                .frame(width: 36, height: 36)
        }
        .buttonStyle(.plain)
    }
}

struct ArtistPopularTrackRow: View {
    @ObservedObject var appState: AppState
    let rank: Int
    let title: String
    var subtitle: String?
    var artPath: String?
    var liked: Bool
    let onTap: () -> Void
    let onMenu: () -> Void
    let onLikeToggle: () -> Void
    @State private var artURL: URL?

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onTap) {
                HStack(spacing: 12) {
                    Text("\(rank)")
                        .font(.body)
                        .foregroundStyle(.white.opacity(0.45))
                        .frame(width: 24, alignment: .trailing)
                    BockArtwork(url: artURL, size: 48, cornerRadius: 4)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.body)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        if let subtitle, !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.55))
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            .buttonStyle(.plain)
            TrackLikeButton(liked: liked, onToggle: onLikeToggle)
            Button(action: onMenu) {
                BockIcon(icon: .moreVert, size: 20)
                    .foregroundStyle(.white.opacity(0.65))
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .task(id: artPath) {
            guard let artPath,
                  let str = await appState.repository.artworkURL(for: artPath),
                  let url = URL(string: str) else {
                artURL = nil
                return
            }
            artURL = url
        }
    }
}

struct DetailStickyMiniHeader: View {
    let title: String
    let onPlay: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        HStack(spacing: 8) {
            Button { dismiss() } label: {
                BockIcon(icon: .arrowBack, size: 24)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            Text(title)
                .font(.headline.weight(.semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
            Spacer(minLength: 0)
            Button(action: onPlay) {
                BockIcon(icon: .playArrow, size: 24)
                    .foregroundStyle(BockColors.green)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.92))
    }
}

struct DiscHeaderRow: View {
    let discNumber: Int
    let trackCount: Int
    let totalSeconds: Int
    let onPlayDisc: () -> Void

    var body: some View {
        HStack {
            Text("DISC \(discNumber)")
                .font(.subheadline.weight(.bold))
                .tracking(0.5)
                .foregroundStyle(.white)
            Button(action: onPlayDisc) {
                BockIcon(icon: .playArrow, size: 18)
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            Spacer()
            Text(PlexampFormat.albumSummary(trackCount: trackCount, totalSeconds: totalSeconds))
                .font(.caption)
                .foregroundStyle(.white.opacity(0.55))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }
}

struct DetailEntitySheet: View {
    @ObservedObject var appState: AppState
    let title: String
    var rating: RatingTarget?
    let actions: [DetailSheetAction]
    let onDismiss: () -> Void

    @State private var stars = 0
    @State private var loadingRating = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(actions.enumerated()), id: \.offset) { _, action in
                        Button(action: {
                            action.handler()
                            onDismiss()
                        }) {
                            Label(action.label, systemImage: action.systemImage)
                                .foregroundStyle(.white)
                        }
                    }
                }
                if let rating {
                    Section("Rating") {
                        if loadingRating {
                            ProgressView()
                        } else {
                            HStack(spacing: 8) {
                                ForEach(1...5, id: \.self) { value in
                                    Button {
                                        Task { await setStars(value, target: rating) }
                                    } label: {
                                        Image(systemName: value <= stars ? "star.fill" : "star")
                                            .foregroundStyle(value <= stars ? BockColors.gold : .white.opacity(0.45))
                                    }
                                    .buttonStyle(.plain)
                                }
                                if stars > 0 {
                                    Button("Clear") {
                                        Task { await setStars(0, target: rating) }
                                    }
                                    .font(.caption)
                                }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color(red: 0.07, green: 0.07, blue: 0.07))
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done", action: onDismiss)
                }
            }
        }
        .presentationDetents([.medium])
        .task(id: rating?.id) {
            guard let rating else {
                stars = 0
                loadingRating = false
                return
            }
            loadingRating = true
            stars = (try? await appState.repository.ratingStars(kind: rating.kind, id: rating.id)) ?? 0
            loadingRating = false
        }
    }

    private func setStars(_ value: Int, target: RatingTarget) async {
        do {
            try await appState.repository.setRating(
                kind: target.kind,
                id: target.id,
                stars: value,
                title: target.title,
                artist: target.artist,
                album: target.album
            )
            stars = value
        } catch {
            appState.toast = error.localizedDescription
        }
    }
}

struct DetailSheetAction {
    let label: String
    let systemImage: String
    let handler: () -> Void
}

struct DetailShareSheet: View {
    @ObservedObject var appState: AppState
    let title: String
    let deepLink: String
    let onDismiss: () -> Void

    var body: some View {
        DetailEntitySheet(
            appState: appState,
            title: title,
            rating: nil,
            actions: [
                DetailSheetAction(label: "Copy link", systemImage: "link") {
                    UIPasteboard.general.string = deepLink
                    appState.toast = "Link copied"
                },
            ],
            onDismiss: onDismiss
        )
    }
}

struct SpotifyRatedRow: View {
    let artistName: String
    let artURL: URL?
    let ratedCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    BockArtwork(url: artURL, size: 48, cornerRadius: 24)
                    Image(systemName: "heart.fill")
                        .font(.caption)
                        .foregroundStyle(BockColors.green)
                        .padding(2)
                        .background(.black)
                        .clipShape(Circle())
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Highly rated")
                        .font(.body.weight(.bold))
                        .foregroundStyle(.white)
                    Text("\(ratedCount) tracks · \(artistName)")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.white.opacity(0.55))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Genre hero banner

struct GenreHeroBanner: View {
    let genreName: String
    let trackCount: Int
    let artURL: URL?
    var remoteOk: Bool = true
    let onPlayRadio: () -> Void
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    LinearGradient(
                        colors: [BockColors.navy, BockColors.surfaceVariant],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 148)
            .clipped()

            LinearGradient(
                colors: [.black.opacity(0.15), .black.opacity(0.75)],
                startPoint: .top,
                endPoint: .bottom
            )
            VStack(alignment: .leading, spacing: 4) {
                Text(genreName)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(.white)
                if trackCount > 0 {
                    Text("\(trackCount) tracks in library")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.75))
                }
                Button(action: onPlayRadio) {
                    HStack(spacing: 6) {
                        BockIcon(icon: .playArrow, size: 18)
                        Text("Play radio")
                            .fontWeight(.semibold)
                    }
                    .foregroundStyle(.black)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(BockColors.green)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(!remoteOk)
                .opacity(remoteOk ? 1 : 0.5)
                .padding(.top, 6)
            }
            .padding(16)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .task(id: artURL) {
            guard let artURL else {
                image = nil
                return
            }
            if let cached = ArtworkImageCache.memoryImage(for: artURL) {
                image = cached
                return
            }
            image = await ArtworkImageCache.load(artURL)
        }
    }
}

// MARK: - Home tile overlay

struct HomeTileOverlayPlay: View {
    let onPlay: () -> Void

    var body: some View {
        Button(action: onPlay) {
            BockIcon(icon: .playArrow, size: 22)
                .foregroundStyle(.black)
                .frame(width: 48, height: 48)
                .background(BockColors.green)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.35), radius: 4, y: 2)
        }
        .buttonStyle(.plain)
        .padding(8)
    }
}

// MARK: - Formatting

enum PlexampFormat {
    static func trackDuration(_ seconds: Int) -> String {
        let m = seconds / 60
        let s = seconds % 60
        return String(format: "%d:%02d", m, s)
    }

    static func albumSummary(trackCount: Int, totalSeconds: Int) -> String {
        let mins = totalSeconds / 60
        return "\(trackCount) tracks · \(mins) min"
    }
}

// MARK: - Screen wrapper

struct PlexampDetailScreen<Content: View>: View {
    let artURL: URL?
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        ZStack {
            ArtBackdrop(url: artURL)
            VStack(spacing: 0) {
                PlexampInlineTopBar(title: title)
                ScrollView {
                    content()
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }
}
