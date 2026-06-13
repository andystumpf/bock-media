import Foundation

enum BockAPIError: LocalizedError {
    case noServerConfigured
    case invalidURL
    case httpStatus(Int, String?)
    case decoding(Error)
    case transport(Error)

    var errorDescription: String? {
        switch self {
        case .noServerConfigured:
            return "No server URL configured"
        case .invalidURL:
            return "Invalid URL"
        case .httpStatus(let code, let body):
            if code == 401 { return "Authentication failed — check username, password, and mobile API token" }
            if code == 403 { return "External API blocked — set mobileApi.allowExternalAccess in config.json" }
            return body ?? "HTTP \(code)"
        case .decoding(let error):
            return "Invalid response: \(error.localizedDescription)"
        case .transport(let error):
            return error.localizedDescription
        }
    }
}
