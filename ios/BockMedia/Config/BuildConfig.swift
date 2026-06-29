import Foundation

/// Build-time defaults mirroring Android `BuildConfig` / `local.properties` fallbacks.
enum BuildConfig {
    static let localServerURL = value(
        keys: ["BOCK_LOCAL_SERVER_URL"],
        default: "http://127.0.0.1:3001"
    )
    static let externalServerURL = value(
        keys: ["BOCK_EXTERNAL_SERVER_URL"],
        default: "http://127.0.0.1:3001"
    )
    static let mobileApiToken = value(keys: ["BOCK_MOBILE_API_TOKEN"], default: "")
    static let adminUser = value(keys: ["BOCK_ADMIN_USER"], default: "")
    static let adminPassword = value(keys: ["BOCK_ADMIN_PASSWORD"], default: "")

    private static func value(keys: [String], default defaultValue: String) -> String {
        for key in keys {
            if let v = Bundle.main.object(forInfoDictionaryKey: key) as? String,
               !v.trimmingCharacters(in: .whitespaces).isEmpty,
               !v.hasPrefix("$(") {
                return v
            }
            if let env = ProcessInfo.processInfo.environment[key],
               !env.trimmingCharacters(in: .whitespaces).isEmpty {
                return env
            }
        }
        return defaultValue
    }
}
