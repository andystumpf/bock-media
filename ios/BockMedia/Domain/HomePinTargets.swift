import Foundation

enum HomePinTargets {
    struct Target: Equatable {
        let sectionId: String
        let title: String
    }

    static func pinEligible() -> [Target] {
        var out: [Target] = [
            Target(sectionId: "recent-playlists", title: "Recent playlists"),
            Target(sectionId: "top-mixes", title: "Your top mixes"),
            Target(sectionId: "daily-mixes", title: "New daily mixes"),
            Target(sectionId: "discover", title: "Discover"),
            Target(sectionId: "explore-themes", title: "Explore genres & worlds"),
            Target(sectionId: "more-playlists", title: "More playlists"),
        ]
        for mood in HomeMoodSections.all() {
            out.append(Target(sectionId: "mood-\(mood.id)", title: mood.title))
        }
        for decade in HomeDecadeSections.all() {
            out.append(Target(sectionId: "decade-\(decade.id)", title: decade.title))
        }
        return out
    }

    static func titleFor(sectionId: String) -> String {
        pinEligible().first { $0.sectionId == sectionId }?.title ?? sectionId
    }

    static func suggestSectionId(playlistName: String) -> String {
        let text = playlistName.lowercased()
        var bestId: String?
        var bestScore = 0
        for decade in HomeDecadeSections.all() {
            if HomeFeedRules.playlistMatchesDecade(text, decadeId: decade.id) {
                return "decade-\(decade.id)"
            }
        }
        for mood in HomeMoodSections.all() {
            var score = 0
            for kw in mood.theme.playlistKeywords where text.contains(kw) { score += 2 }
            for kw in mood.theme.genreKeywords where text.contains(kw) { score += 1 }
            if score > bestScore {
                bestScore = score
                bestId = "mood-\(mood.id)"
            }
        }
        if let bestId, bestScore > 0 { return bestId }
        if text.contains("morning") || text.contains("weekday") || text.contains("brunch") {
            return "mood-sunday-morning"
        }
        if text.contains("calm") || text.contains("relax") || text.contains("wind") {
            return "mood-wind-down"
        }
        if text.contains("party") || text.contains("dance") { return "mood-party" }
        if text.contains("road") || text.contains("drive") || text.contains("trip") {
            return "mood-road-trip"
        }
        return "recent-playlists"
    }
}
