import Foundation
import UIKit

enum DeviceAnalyticsReporter {
    private static var lastPlaybackReportMs: Int64 = 0
    private static let playbackThrottleMs: Int64 = 4_000

    static func reportConnect(repository: BockMediaRepository) {
        post(repository: repository, event: "connect") { _ in }
    }

    static func reportPlay(repository: BockMediaRepository, track: LocalTrack) {
        post(repository: repository, event: "play") { body in
            body["track"] = track.title
            if let artist = track.artist { body["artist"] = artist }
            if let album = track.album { body["album"] = album }
            body["filepath"] = track.path
        }
        reportPlayback(
            repository: repository,
            track: track,
            playing: true,
            offsetMs: 0,
            durationMs: 0,
            force: true
        )
    }

    static func reportPlayback(
        repository: BockMediaRepository,
        track: LocalTrack,
        playing: Bool,
        offsetMs: Int64,
        durationMs: Int64,
        force: Bool = false
    ) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if !force, now - lastPlaybackReportMs < playbackThrottleMs { return }
        lastPlaybackReportMs = now
        post(repository: repository, event: "playback") { body in
            body["track"] = track.title
            if let artist = track.artist { body["artist"] = artist }
            if let album = track.album { body["album"] = album }
            body["filepath"] = track.path
            body["playing"] = playing
            body["paused"] = !playing
            body["offset_ms"] = offsetMs
            body["duration_ms"] = durationMs
        }
    }

    static func clearPlayback(repository: BockMediaRepository) {
        lastPlaybackReportMs = 0
        post(repository: repository, event: "playback") { body in
            body["stopped"] = true
        }
    }

    private static func post(
        repository: BockMediaRepository,
        event: String,
        extra: @escaping @Sendable (inout [String: Any]) -> Void
    ) {
        Task { @MainActor in
            var body: [String: Any] = [
                "clientId": ClientIdStore.clientId(),
                "platform": "ios",
                "deviceName": deviceLabel(),
                "event": event,
            ]
            if let memberId = ActiveProfileStore.activeMemberId() {
                body["memberId"] = memberId
            }
            extra(&body)
            try? await repository.reportClientEvent(body)
        }
    }

    private static func deviceLabel() -> String {
        let name = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? "This iPhone" : "iOS · \(name)"
    }
}
