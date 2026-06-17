import Foundation

enum SearchSongFilter {
    static func filter(query: String, songs: [SearchHit]) -> [SearchHit] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard q.count >= 2 else { return [] }
        return songs.filter { matchesSearchSongTitle(q: q, title: $0.title, album: $0.album) }
    }

    static func matchesSearchSongTitle(q: String, title: String?, album: String?) -> Bool {
        let tl = title?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard tl.contains(q) else { return false }
        let al = album?.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        for sep in [" - from ", " – from "] {
            if let idx = tl.range(of: sep)?.lowerBound {
                let primary = String(tl[..<idx])
                let suffix = String(tl[idx...].dropFirst(sep.count))
                if al.contains(q), !primary.contains(q), suffix.contains(q) { return false }
            }
        }
        if let dash = tl.range(of: " - ") {
            let primary = String(tl[..<dash.lowerBound])
            let suffix = String(tl[dash.upperBound...])
            if al.contains(q), !primary.contains(q), suffix.contains(q) { return false }
        }
        return true
    }
}
