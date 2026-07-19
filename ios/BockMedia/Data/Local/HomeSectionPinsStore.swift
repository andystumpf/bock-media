import Foundation

enum HomeSectionPinsStore {
    private static let defaultsKey = "home_section_pins"

    static func load() -> [HomeSectionPin] {
        guard let raw = UserDefaults.standard.string(forKey: defaultsKey), !raw.isEmpty else { return [] }
        return decode(raw)
    }

    static func pin(sectionId: String, playlistId: String, playlistName: String) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var updated = load().filter { !($0.sectionId == sectionId && $0.playlistId == playlistId) }
        updated.append(HomeSectionPin(
            sectionId: sectionId,
            playlistId: playlistId,
            playlistName: playlistName,
            pinnedAtMs: now
        ))
        save(updated)
        HomeFeedCache.invalidate()
        Task { @MainActor in ClientPrefsSync.schedulePush() }
    }

    static func pinnedSections(playlistId: String) -> [String] {
        load().filter { $0.playlistId == playlistId }.map(\.sectionId)
    }

    static func exportJson() -> String? {
        let pins = load()
        guard !pins.isEmpty else { return nil }
        return encode(pins)
    }

    static func importJson(_ raw: String) {
        guard !raw.isEmpty else { return }
        save(decode(raw))
    }

    private static func save(_ pins: [HomeSectionPin]) {
        UserDefaults.standard.set(encode(pins), forKey: defaultsKey)
    }

    private static func encode(_ pins: [HomeSectionPin]) -> String {
        let rows = pins.map { pin -> [String: Any] in
            [
                "sectionId": pin.sectionId,
                "playlistId": pin.playlistId,
                "playlistName": pin.playlistName,
                "pinnedAtMs": pin.pinnedAtMs,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }

    private static func decode(_ raw: String) -> [HomeSectionPin] {
        guard let data = raw.data(using: .utf8),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return rows.compactMap { row in
            guard let sectionId = row["sectionId"] as? String,
                  let playlistId = row["playlistId"] as? String else { return nil }
            let playlistName = row["playlistName"] as? String ?? ""
            let pinnedAtMs = (row["pinnedAtMs"] as? NSNumber)?.int64Value
                ?? Int64(Date().timeIntervalSince1970 * 1000)
            return HomeSectionPin(
                sectionId: sectionId,
                playlistId: playlistId,
                playlistName: playlistName,
                pinnedAtMs: pinnedAtMs
            )
        }
    }
}
