import SwiftUI
import WidgetKit

private enum WidgetTheme {
    static let background = Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255)
    static let card = Color(red: 0x1C / 255, green: 0x1C / 255, blue: 0x1C / 255)
    static let cardHeader = Color(red: 0x24 / 255, green: 0x24 / 255, blue: 0x24 / 255)
    static let localHeader = Color(red: 0x1A / 255, green: 0x3D / 255, blue: 0x28 / 255)
    static let green = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)
    static let onSurface = Color.white
    static let muted = Color(red: 0xA7 / 255, green: 0xB3 / 255, blue: 0xC7 / 255)
    static let divider = Color.white.opacity(0.1)
}

struct NowPlayingEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSessionSnapshot?
}

struct NowPlayingProvider: TimelineProvider {
    func placeholder(in context: Context) -> NowPlayingEntry {
        NowPlayingEntry(date: Date(), snapshot: previewSnapshot)
    }

    func getSnapshot(in context: Context, completion: @escaping (NowPlayingEntry) -> Void) {
        completion(NowPlayingEntry(date: Date(), snapshot: WidgetSessionStore.load() ?? previewSnapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NowPlayingEntry>) -> Void) {
        let snap = WidgetSessionStore.load()
        let entry = NowPlayingEntry(date: Date(), snapshot: snap)
        let items = WidgetSnapshotLogic.itemsForWidgetDisplay(snap?.items ?? [])
        let playing = WidgetSnapshotLogic.playingCount(items) > 0
        let seconds = playing ? 3 : 30
        let next = Calendar.current.date(byAdding: .second, value: seconds, to: Date()) ?? Date()
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private var previewSnapshot: WidgetSessionSnapshot {
        WidgetSessionSnapshot(
            baseURL: "http://localhost",
            updatedAt: Date(),
            items: [
                WidgetNowPlayingItem(
                    deviceId: WidgetSnapshotLogic.localDeviceId,
                    deviceName: "This iPhone",
                    track: "Preview Track",
                    artist: "Preview Artist",
                    paused: false,
                    isLocal: true,
                    canControl: true
                ),
                WidgetNowPlayingItem(
                    deviceId: "echo-1",
                    deviceName: "Master Bathroom",
                    track: "Another Song",
                    artist: "Another Artist",
                    paused: true,
                    isLocal: false,
                    canControl: true
                ),
            ],
            controlsAvailable: true
        )
    }
}

struct NowPlayingWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NowPlayingEntry

    private var nowPlayingItems: [WidgetNowPlayingItem] {
        WidgetSnapshotLogic.itemsForWidgetDisplay(entry.snapshot?.items ?? [])
    }

    private var deviceLimit: Int {
        family == .systemLarge ? WidgetSnapshotLogic.maxLargeDevices : WidgetSnapshotLogic.maxMediumDevices
    }

    private var isCompact: Bool { family == .systemMedium }

    var body: some View {
        VStack(alignment: .leading, spacing: isCompact ? 6 : 8) {
            header
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(isCompact ? 10 : 12)
        .containerBackground(for: .widget) {
            WidgetTheme.background
        }
    }

    @ViewBuilder
    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Bock Media")
                .font(.caption.weight(.bold))
                .foregroundStyle(WidgetTheme.green)
            Spacer()
            if nowPlayingItems.count > 1 {
                Text("\(nowPlayingItems.count) devices")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(WidgetTheme.muted)
            } else if !nowPlayingItems.isEmpty {
                let count = WidgetSnapshotLogic.playingCount(nowPlayingItems)
                Text(count == 1 ? "1 playing" : "Paused")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(WidgetTheme.muted)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !nowPlayingItems.isEmpty {
            VStack(spacing: isCompact ? 6 : 8) {
                ForEach(Array(nowPlayingItems.prefix(deviceLimit).enumerated()), id: \.element.id) { index, item in
                    WidgetDeviceCard(item: item, compact: isCompact)
                    if index < min(nowPlayingItems.count, deviceLimit) - 1 {
                        Rectangle()
                            .fill(WidgetTheme.divider)
                            .frame(height: 1)
                    }
                }
            }
        } else if let recent = entry.snapshot?.recentPlaylists, !recent.isEmpty {
            recentContent(recent: recent)
        } else if entry.snapshot?.baseURL != nil {
            emptyConnected
        } else {
            Text("Open Bock Media to connect")
                .font(.subheadline)
                .foregroundStyle(WidgetTheme.muted)
        }
    }

    private var emptyConnected: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Nothing playing")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(WidgetTheme.onSurface)
            Text("Open the app to start music")
                .font(.caption)
                .foregroundStyle(WidgetTheme.muted)
        }
    }

    @ViewBuilder
    private func recentContent(recent: [WidgetRecentPlaylist]) -> some View {
        Text("Recently played")
            .font(.caption.weight(.semibold))
            .foregroundStyle(WidgetTheme.muted)
        ForEach(recent.prefix(WidgetRecentPlaylistLimit.max)) { item in
            if let url = item.playURL {
                Link(destination: url) { recentRow(item) }
            } else {
                recentRow(item)
            }
        }
    }

    private func recentRow(_ item: WidgetRecentPlaylist) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.title)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(WidgetTheme.onSurface)
            if let subtitle = item.subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.muted)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct WidgetDeviceCard: View {
    let item: WidgetNowPlayingItem
    let compact: Bool

    private var deviceLabel: String { WidgetSnapshotLogic.deviceLabel(for: item) }
    private var trackTitle: String {
        let t = item.track?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return t.isEmpty ? "Playing" : t
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            deviceHeader
            trackRow
                .padding(.horizontal, compact ? 8 : 10)
                .padding(.vertical, compact ? 8 : 10)
        }
        .background(WidgetTheme.card)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay {
            RoundedRectangle(cornerRadius: 10)
                .strokeBorder(item.isLocal ? WidgetTheme.green.opacity(0.35) : WidgetTheme.divider, lineWidth: 1)
        }
        .widgetURL(WidgetSnapshotLogic.openDeviceURL(deviceId: item.deviceId))
    }

    private var deviceHeader: some View {
        HStack(spacing: 6) {
            Image(systemName: item.isLocal ? "iphone" : "hifispeaker.fill")
                .font(.caption2.weight(.bold))
            Text(deviceLabel)
                .font(.caption.weight(.bold))
                .lineLimit(1)
            if item.paused {
                Text("· Paused")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(WidgetTheme.muted)
            }
            Spacer(minLength: 4)
            if item.canControl {
                controlButtons
            }
        }
        .foregroundStyle(item.isLocal ? WidgetTheme.green : (item.paused ? WidgetTheme.muted : WidgetTheme.onSurface))
        .padding(.horizontal, compact ? 8 : 10)
        .padding(.vertical, compact ? 6 : 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(item.isLocal ? WidgetTheme.localHeader : WidgetTheme.cardHeader)
    }

    private var trackRow: some View {
        HStack(alignment: .center, spacing: compact ? 8 : 10) {
            artwork
            VStack(alignment: .leading, spacing: 2) {
                Text(trackTitle)
                    .font(compact ? .caption.weight(.bold) : .subheadline.weight(.bold))
                    .foregroundStyle(WidgetTheme.onSurface)
                    .lineLimit(compact ? 1 : 2)
                if let artist = item.artist?.trimmingCharacters(in: .whitespacesAndNewlines), !artist.isEmpty {
                    Text(artist)
                        .font(.caption2)
                        .foregroundStyle(WidgetTheme.muted)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder
    private var artwork: some View {
        let size: CGFloat = compact ? 36 : 48
        Group {
            if let urlString = item.artURL, let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    default:
                        artworkPlaceholder(size: size)
                    }
                }
            } else {
                artworkPlaceholder(size: size)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func artworkPlaceholder(size: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 6)
            .fill(WidgetTheme.divider)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: "music.note")
                    .font(.caption2)
                    .foregroundStyle(WidgetTheme.muted)
            }
    }

    private var controlButtons: some View {
        HStack(spacing: 4) {
            controlLink(
                action: WidgetSnapshotLogic.controlAction(for: item),
                icon: item.paused ? .playArrow : .pause,
                label: "\(item.paused ? "Play" : "Pause") \(deviceLabel)"
            )
            controlLink(action: "next", icon: .skipNext, label: "Skip on \(deviceLabel)")
        }
    }

    @ViewBuilder
    private func controlLink(action: String, icon: BockIcons, label: String) -> some View {
        if let url = WidgetSnapshotLogic.controlURL(deviceId: item.deviceId, action: action) {
            Link(destination: url) {
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(item.paused && action == "play" ? 0.2 : 0.12))
                        .frame(width: compact ? 28 : 32, height: compact ? 28 : 32)
                    BockIcon(icon: icon, size: compact ? 14 : 16)
                        .foregroundStyle(WidgetTheme.onSurface)
                }
            }
            .accessibilityLabel(label)
        }
    }
}

@main
struct BockMediaWidgetBundle: WidgetBundle {
    init() {
        // The widget runs in its own process, so it can't share the app's
        // in-memory artwork cache. Give it a disk-backed URLCache so AsyncImage
        // artwork loads are cached between timeline refreshes.
        URLCache.shared = URLCache(
            memoryCapacity: 16_000_000,
            diskCapacity: 128_000_000,
            diskPath: "bock_widget_artwork"
        )
    }

    var body: some Widget {
        NowPlayingWidget()
    }
}

struct NowPlayingWidget: Widget {
    let kind = "NowPlayingWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: NowPlayingProvider()) { entry in
            NowPlayingWidgetView(entry: entry)
        }
        .configurationDisplayName("Now Playing")
        .description("Each row is one device — play, pause, and skip affect only that speaker or this iPhone.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}
