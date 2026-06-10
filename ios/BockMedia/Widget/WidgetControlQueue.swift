import Foundation

struct WidgetControlCommand: Codable {
    var deviceId: String
    var action: String
    var createdAt: Date
}

enum WidgetControlQueue {
    private static let fileName = "pending_control.json"

    private static var fileURL: URL? {
        WidgetSessionStore.containerURL?.appendingPathComponent(fileName, isDirectory: false)
    }

    static func enqueue(deviceId: String, action: String) {
        let cmd = WidgetControlCommand(deviceId: deviceId, action: action, createdAt: Date())
        guard let url = fileURL,
              let data = try? JSONEncoder().encode(cmd) else { return }
        try? data.write(to: url, options: .atomic)
    }

    static func dequeue() -> WidgetControlCommand? {
        guard let url = fileURL,
              let data = try? Data(contentsOf: url),
              let cmd = try? JSONDecoder().decode(WidgetControlCommand.self, from: data) else { return nil }
        try? FileManager.default.removeItem(at: url)
        return cmd
    }
}
