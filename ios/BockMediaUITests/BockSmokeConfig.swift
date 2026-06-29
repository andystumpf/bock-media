import Foundation

enum BockSmokeConfig {
    static var defaultTimeout: TimeInterval {
        if let raw = ProcessInfo.processInfo.environment["SMOKE_TIMEOUT_MS"],
           let ms = Double(raw), ms > 0 {
            return ms / 1000
        }
        return 45
    }

    static var searchQuery: String {
        ProcessInfo.processInfo.environment["SMOKE_SEARCH_QUERY"] ?? "love"
    }

    static var shortSearchQuery: String {
        ProcessInfo.processInfo.environment["SMOKE_SHORT_SEARCH_QUERY"] ?? "ab"
    }

    static var serverURL: String {
        ProcessInfo.processInfo.environment["BOCK_TEST_SERVER_URL"] ?? ""
    }

    static var apiToken: String {
        ProcessInfo.processInfo.environment["BOCK_TEST_API_TOKEN"] ?? ""
    }
}
