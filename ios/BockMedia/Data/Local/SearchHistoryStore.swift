import Foundation

enum SearchHistoryStore {
    private static let key = "search_history_queries"
    private static let separator = "\u{0001}"
    private static let maxItems = 10

    static func queries() -> [String] {
        UserDefaults.standard.string(forKey: key)?
            .split(separator: Character(separator))
            .map { String($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty } ?? []
    }

    static func add(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        guard trimmed.count >= 2 else { return }
        var current = queries().filter { $0.caseInsensitiveCompare(trimmed) != .orderedSame }
        current.insert(trimmed, at: 0)
        save(Array(current.prefix(maxItems)))
    }

    static func remove(_ query: String) {
        save(queries().filter { $0.caseInsensitiveCompare(query) != .orderedSame })
    }

    private static func save(_ items: [String]) {
        if items.isEmpty {
            UserDefaults.standard.removeObject(forKey: key)
        } else {
            UserDefaults.standard.set(items.joined(separator: separator), forKey: key)
        }
    }
}
