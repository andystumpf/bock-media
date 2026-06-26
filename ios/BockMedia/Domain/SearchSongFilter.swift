import Foundation

enum SearchSongFilter {
    static func filter(query: String, songs: [SearchHit]) -> [SearchHit] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 2 else { return [] }
        return songs.filter { matchesSearchSongTitle(q: q, title: $0.title, album: $0.album, artist: $0.artist) }
    }

    static func matchesSearchSongTitle(q: String, title: String?, album: String?, artist: String? = nil) -> Bool {
        if SearchPrefixMatch.fieldMatchesQuery(q: q, text: artist) { return true }
        guard SearchPrefixMatch.fieldMatchesQuery(q: q, text: title) else { return false }
        let tl = title?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let al = album?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        for sep in [" - from ", " – from "] {
            if let idx = tl.range(of: sep)?.lowerBound {
                let primary = String(tl[..<idx])
                let suffix = String(tl[idx...].dropFirst(sep.count))
                if SearchPrefixMatch.fieldMatchesQuery(q: q, text: al),
                   !SearchPrefixMatch.fieldMatchesQuery(q: q, text: primary),
                   SearchPrefixMatch.fieldMatchesQuery(q: q, text: suffix) {
                    return false
                }
            }
        }
        if let dash = tl.range(of: " - ") {
            let primary = String(tl[..<dash.lowerBound])
            let suffix = String(tl[dash.upperBound...])
            if SearchPrefixMatch.fieldMatchesQuery(q: q, text: al),
               !SearchPrefixMatch.fieldMatchesQuery(q: q, text: primary),
               SearchPrefixMatch.fieldMatchesQuery(q: q, text: suffix) {
                return false
            }
        }
        return true
    }
}

enum SearchPrefixMatch {
    static func compact(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: "[^a-z0-9]", with: "", options: .regularExpression)
    }

    static func acronymCompact(_ text: String) -> String {
        let parts = text.split { $0 == "." || $0.isWhitespace }.map(String.init).filter { !$0.isEmpty }
        if parts.count > 1 {
            return parts.map { String($0.prefix(1)).lowercased() }.joined()
        }
        let words = text.split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { !$0.isEmpty }
        if words.count > 1 {
            return words.map { String($0.prefix(1)).lowercased() }.joined()
        }
        return compact(text)
    }

    static func fieldMatchesQuery(q: String, text: String?) -> Bool {
        let qc = compact(q)
        guard !qc.isEmpty, let text, !text.isEmpty else { return false }
        let tc = compact(text)
        if tc.hasPrefix(qc) { return true }
        let ac = acronymCompact(text)
        if ac.hasPrefix(qc) { return true }
        let words = text.split { !$0.isLetter && !$0.isNumber }.map(String.init).filter { !$0.isEmpty }
        for word in words {
            if compact(word).hasPrefix(qc) { return true }
            if acronymCompact(word).hasPrefix(qc) { return true }
        }
        return false
    }
}
