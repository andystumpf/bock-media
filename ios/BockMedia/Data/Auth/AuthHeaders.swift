import Foundation

enum AuthHeaders {
    static func apply(
        to request: inout URLRequest,
        localHosts: Set<String>,
        username: String?,
        password: String?,
        token: String?
    ) {
        guard let host = request.url?.host?.lowercased(), !localHosts.contains(host) else { return }

        let token = token?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        let user = username?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        let pass = password?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank

        if let token, let user, let pass {
            request.setValue(basicAuth(user: user, pass: pass), forHTTPHeaderField: "Authorization")
            request.setValue(token, forHTTPHeaderField: "X-BockMedia-Token")
        } else if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else if let user, let pass {
            request.setValue(basicAuth(user: user, pass: pass), forHTTPHeaderField: "Authorization")
        }
    }

    private static func basicAuth(user: String, pass: String) -> String {
        let raw = "\(user):\(pass)"
        let data = Data(raw.utf8).base64EncodedString()
        return "Basic \(data)"
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
