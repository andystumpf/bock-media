import SwiftUI

private func activeLyricIndex(lines: [LyricLine], positionMs: Int64) -> Int {
    guard !lines.isEmpty else { return -1 }
    var lo = 0
    var hi = lines.count - 1
    var ans = -1
    while lo <= hi {
        let mid = (lo + hi) / 2
        if lines[mid].timeMs <= positionMs {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

struct LyricsPanel: View {
    let lyrics: LyricsResponse?
    let loading: Bool
    let error: String?
    let positionMs: Int64
    var offsetMs: Int = 0
    var onOffsetChange: ((Int) -> Void)?

    var body: some View {
        Group {
            if loading {
                ProgressView()
                    .tint(.white)
            } else if let error {
                Text(error)
                    .font(.subheadline)
                    .foregroundStyle(.white.opacity(0.55))
                    .multilineTextAlignment(.center)
                    .padding(24)
            } else if let lyrics {
                if !lyrics.lines.isEmpty {
                    SyncedLyricsList(
                        lines: lyrics.lines,
                        positionMs: positionMs + Int64(offsetMs),
                        offsetMs: offsetMs,
                        onOffsetChange: onOffsetChange
                    )
                } else if !lyrics.plain.isEmpty {
                    PlainLyricsText(text: lyrics.plain)
                } else {
                    Text("No lyrics available")
                        .foregroundStyle(.white.opacity(0.55))
                }
            } else {
                Text("No lyrics available")
                    .foregroundStyle(.white.opacity(0.55))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SyncedLyricsList: View {
    let lines: [LyricLine]
    let positionMs: Int64
    let offsetMs: Int
    var onOffsetChange: ((Int) -> Void)?

    var body: some View {
        let activeIndex = activeLyricIndex(lines: lines, positionMs: positionMs)
        ZStack(alignment: .bottom) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 18) {
                        ForEach(Array(lines.enumerated()), id: \.element.id) { index, line in
                            Text(line.text)
                                .font(.system(size: index == activeIndex ? 26 : 22, weight: index == activeIndex ? .bold : .regular))
                                .foregroundStyle(lineColor(index: index, activeIndex: activeIndex))
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                                .id(index)
                        }
                    }
                    .padding(.horizontal, 28)
                    .padding(.vertical, 48)
                }
                .onChange(of: activeIndex) { _, idx in
                    guard idx >= 0 else { return }
                    withAnimation(.easeInOut(duration: 0.25)) {
                        proxy.scrollTo(idx, anchor: .center)
                    }
                }
            }
            if let onOffsetChange {
                LyricsOffsetControls(offsetMs: offsetMs, onOffsetChange: onOffsetChange)
                    .padding(.bottom, 8)
            }
        }
    }

    private func lineColor(index: Int, activeIndex: Int) -> Color {
        if index == activeIndex { return .white }
        if activeIndex >= 0 && index < activeIndex { return .white.opacity(0.45) }
        return .white.opacity(0.28)
    }
}

private struct LyricsOffsetControls: View {
    let offsetMs: Int
    let onOffsetChange: (Int) -> Void
    private let stepMs = 500

    var body: some View {
        HStack(spacing: 4) {
            Button { onOffsetChange(offsetMs - stepMs) } label: {
                Image(systemName: "minus")
                    .foregroundStyle(.white)
            }
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.85))
                .frame(minWidth: 36)
            Button { onOffsetChange(offsetMs + stepMs) } label: {
                Image(systemName: "plus")
                    .foregroundStyle(.white)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.55))
        .clipShape(Capsule())
    }

    private var label: String {
        if offsetMs == 0 { return "Sync" }
        if offsetMs > 0 { return String(format: "+%.1fs", Double(offsetMs) / 1000) }
        return String(format: "%.1fs", Double(offsetMs) / 1000)
    }
}

private struct PlainLyricsText: View {
    let text: String

    var body: some View {
        VStack(spacing: 12) {
            Text("Not synced — add a .lrc file for timed lyrics")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.7))
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Color.black.opacity(0.45))
                .clipShape(Capsule())
            ScrollView {
                Text(text)
                    .font(.body)
                    .foregroundStyle(.white.opacity(0.88))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }
        }
    }
}
