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

    static func localHosts(localURL: String?) -> Set<String> {
        var hosts: Set<String> = ["localhost", "127.0.0.1"]
        if let h = host(of: localURL) { hosts.insert(h) }
        return hosts
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
