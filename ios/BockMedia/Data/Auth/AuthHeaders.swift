import Foundation

enum AuthHeaders {
    static func apply(
        to request: inout URLRequest,
        localHosts: Set<String>,
        username: String?,
        password: String?,
        token: String?
    ) {
        for (field, value) in headerFields(username: username, password: password, token: token) {
            request.setValue(value, forHTTPHeaderField: field)
        }
    }

    /// Same auth scheme as Android `BockAuthInterceptor` — always send credentials when configured.
    static func headerFields(
        username: String?,
        password: String?,
        token: String?
    ) -> [String: String] {
        let token = token?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        let user = username?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
        let pass = password?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank

        if let token, let user, let pass {
            return [
                "Authorization": basicAuth(user: user, pass: pass),
                "X-BockMedia-Token": token,
            ]
        }
        if let token {
            return ["Authorization": "Bearer \(token)"]
        }
        if let user, let pass {
            return ["Authorization": basicAuth(user: user, pass: pass)]
        }
        return [:]
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
