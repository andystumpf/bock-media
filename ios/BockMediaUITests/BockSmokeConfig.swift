import Foundation

enum BockSmokeConfig {
    /// Hard ceiling so a stuck shell wait cannot run for an hour.
    static let maxWaitSeconds: TimeInterval = 90

    static var defaultTimeout: TimeInterval {
        if let ms = smokeEnvFile()?.timeoutMs, ms > 0 {
            return min(ms / 1000, maxWaitSeconds)
        }
        if let raw = ProcessInfo.processInfo.environment["SMOKE_TIMEOUT_MS"],
           let ms = Double(raw), ms > 0 {
            return min(ms / 1000, maxWaitSeconds)
        }
        return 30
    }

    static var searchQuery: String {
        ProcessInfo.processInfo.environment["SMOKE_SEARCH_QUERY"] ?? "love"
    }

    static var shortSearchQuery: String {
        ProcessInfo.processInfo.environment["SMOKE_SHORT_SEARCH_QUERY"] ?? "ab"
    }

    static var serverURL: String {
        if let env = ProcessInfo.processInfo.environment["BOCK_TEST_SERVER_URL"]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !env.isEmpty {
            return env
        }
        if let file = smokeEnvFile()?.serverURL, !file.isEmpty { return file }
        return xcconfigValue(keys: ["BOCK_EXTERNAL_SERVER_URL", "BOCK_LOCAL_SERVER_URL"]) ?? ""
    }

    static var apiToken: String {
        if let env = ProcessInfo.processInfo.environment["BOCK_TEST_API_TOKEN"]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !env.isEmpty {
            return env
        }
        if let file = smokeEnvFile()?.apiToken, !file.isEmpty { return file }
        return xcconfigValue(keys: ["BOCK_MOBILE_API_TOKEN"]) ?? ""
    }

    private struct SmokeEnvFile: Decodable {
        let serverURL: String
        let apiToken: String
        let timeoutMs: Double?
    }

    /// Written by scripts/run_ios_smoke_tests.sh before xcodebuild (UI test host may not inherit shell env).
    private static func smokeEnvFile() -> SmokeEnvFile? {
        let path = "/tmp/bock-smoke-env.json"
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else { return nil }
        return try? JSONDecoder().decode(SmokeEnvFile.self, from: data)
    }

    /// Read ios/Config.xcconfig when running from source tree (simulator / local dev).
    private static func xcconfigValue(keys: [String]) -> String? {
        var candidates: [URL] = []
        if let src = ProcessInfo.processInfo.environment["SRCROOT"], !src.isEmpty {
            candidates.append(URL(fileURLWithPath: src).appendingPathComponent("Config.xcconfig"))
        }
        let iosDir = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        candidates.append(iosDir.appendingPathComponent("Config.xcconfig"))
        for path in candidates {
            guard let text = try? String(contentsOf: path, encoding: .utf8) else { continue }
            if let value = parseXcconfig(text, keys: keys) { return value }
        }
        return nil
    }

    private static func parseXcconfig(_ text: String, keys: [String]) -> String? {
        for key in keys {
            for line in text.split(separator: "\n") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard trimmed.hasPrefix("\(key) = ") else { continue }
                let value = trimmed.dropFirst("\(key) = ".count).trimmingCharacters(in: .whitespaces)
                    .replacingOccurrences(of: "/$()/", with: "//")
                if !value.isEmpty { return value }
            }
        }
        return nil
    }
}
