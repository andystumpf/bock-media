import Foundation

enum ServerURL {
    static func normalize(_ raw: String) -> String {
        var url = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if !url.hasPrefix("http://") && !url.hasPrefix("https://") {
            url = "http://\(url)"
        }
        while url.hasSuffix("/") { url.removeLast() }
        return url
    }

    static func host(of raw: String?) -> String? {
        guard let raw, !raw.isEmpty, let url = URL(string: normalize(raw)) else { return nil }
        return url.host?.lowercased()
    }

    /** True for hosts that only work on the dev machine / simulator, never on a physical phone. */
    static func isLoopbackHost(_ raw: String?) -> Bool {
        guard let host = host(of: raw) else { return false }
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    /** Rejects scheme-only values like "http:" that xcconfig can produce when URLs contain //. */
    static func isValidServerURL(_ raw: String?) -> Bool {
        guard let raw else { return false }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        if trimmed == "http:" || trimmed == "https:" { return false }
        guard let host = host(of: trimmed), !host.isEmpty else { return false }
        if host == "http" || host == "https" { return false }
        return true
    }

    static func sanitizedServerURL(_ raw: String?) -> String? {
        guard isValidServerURL(raw) else { return nil }
        return normalize(raw!)
    }

    static func localHosts(localURL: String?) -> Set<String> {
        var hosts: Set<String> = ["localhost", "127.0.0.1"]
        if let h = host(of: localURL) { hosts.insert(h) }
        return hosts
    }

    /** True when the host is only reachable on the home LAN (not over cellular/remote). */
    static func isLanHost(_ url: String?, localURL: String? = nil) -> Bool {
        guard let host = host(of: url) else { return false }
        if localHosts(localURL: localURL).contains(host) { return true }
        if host == "localhost" || host == "127.0.0.1" { return true }
        if host.hasPrefix("192.168.") || host.hasPrefix("10.") { return true }
        return host.hasSuffix(".local")
    }

    static func encodeMediaPath(_ filepath: String) -> String {
        let rel = filepath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return rel.split(separator: "/").map { segment in
            segment.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.union(CharacterSet(charactersIn: "%"))) ?? String(segment)
        }.joined(separator: "/")
    }

    static func artworkURL(base: String, filepath: String?) -> String? {
        guard let filepath, !filepath.isEmpty else { return nil }
        return "\(normalize(base))/artwork/\(encodeMediaPath(filepath))"
    }

    static func streamURL(base: String, filepath: String?) -> String? {
        guard let filepath, !filepath.isEmpty else { return nil }
        return "\(normalize(base))/stream/\(encodeMediaPath(filepath))"
    }
}
