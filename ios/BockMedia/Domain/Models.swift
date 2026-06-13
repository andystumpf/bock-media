import Foundation

struct NowPlayingProgress {
    let elapsedMs: Int64
    let durationMs: Int64

    var fraction: Double {
        guard durationMs > 0 else { return 0 }
        return min(1, max(0, Double(elapsedMs) / Double(durationMs)))
    }
}

func computeNowPlayingProgress(
    timestamp: Double?,
    durationMs: Int64,
    offsetMs: Int64,
    paused: Bool
) -> NowPlayingProgress {
    guard let timestamp, durationMs > 0 else {
        return NowPlayingProgress(elapsedMs: 0, durationMs: durationMs)
    }
    let elapsedSec = paused ? 0.0 : (Date().timeIntervalSince1970 - timestamp)
    let posMs = offsetMs + Int64(elapsedSec * 1000)
    return NowPlayingProgress(elapsedMs: min(posMs, durationMs), durationMs: durationMs)
}

func formatPlaybackTime(seconds: Int64) -> String {
    let sec = max(0, seconds)
    let m = sec / 60
    let s = sec % 60
    return String(format: "%d:%02d", m, s)
}

func buildRoutinePhrase(playlist: String, shuffle: Bool, alias: String = "bock media") -> String {
    let verb = shuffle ? "mix" : "start"
    return "Alexa, ask \(alias) to \(verb) the \(playlist) playlist"
}
