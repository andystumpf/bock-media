import Foundation

struct HomeMoodSection: Equatable {
    let id: String
    let title: String
    let theme: HomeTheme
}

enum HomeMoodSections {
    static let dinner = HomeMoodSection(
        id: "dinner",
        title: "Dinner & entertaining",
        theme: HomeTheme(
            id: "dinner",
            title: "Dinner playlist",
            subtitle: "Cooking, hosting & table music",
            playlistKeywords: ["dinner", "cooking", "kitchen", "entertaining", "cocktail", "wine", "supper", "table", "host", "feast"],
            genreKeywords: ["dinner", "jazz", "lounge", "easy listening"]
        )
    )
    static let french = HomeMoodSection(
        id: "french",
        title: "French music",
        theme: HomeTheme(
            id: "french",
            title: "French favorites",
            subtitle: "Chanson, pop & café culture",
            playlistKeywords: ["french", "français", "francais", "france", "chanson", "paris"],
            genreKeywords: ["french", "français", "francais", "chanson"]
        )
    )
    static let italian = HomeMoodSection(
        id: "italian",
        title: "Italian music",
        theme: HomeTheme(
            id: "italian",
            title: "Italian classics",
            subtitle: "Pop, opera & la dolce vita",
            playlistKeywords: ["italian", "italiano", "italia", "italy", "canzone", "rome"],
            genreKeywords: ["italian", "italiano", "italia"]
        )
    )
    static let workFromHome = HomeMoodSection(
        id: "work-from-home",
        title: "Work from home",
        theme: HomeTheme(
            id: "work-from-home",
            title: "Focus flow",
            subtitle: "Deep work & concentration",
            playlistKeywords: ["work", "focus", "wfh", "concentration", "office", "productivity", "coding", "study", "deep work", "instrumental"],
            genreKeywords: ["ambient", "classical", "electronic", "instrumental"]
        )
    )
    static let roadTrip = HomeMoodSection(
        id: "road-trip",
        title: "Road trip",
        theme: HomeTheme(
            id: "road-trip",
            title: "Highway mix",
            subtitle: "Driving, windows down & open road",
            playlistKeywords: ["road trip", "roadtrip", "driving", "highway", "travel", "car", "journey", "on the road", "windows down"],
            genreKeywords: ["rock", "country", "pop", "classic rock"]
        )
    )
    static let sundayMorning = HomeMoodSection(
        id: "sunday-morning",
        title: "Sunday morning",
        theme: HomeTheme(
            id: "sunday-morning",
            title: "Easy Sunday",
            subtitle: "Brunch, coffee & slow starts",
            playlistKeywords: ["sunday", "morning", "brunch", "coffee", "easy", "wake", "weekend", "sunrise", "lazy"],
            genreKeywords: ["folk", "acoustic", "jazz", "easy listening", "gospel"]
        )
    )
    static let party = HomeMoodSection(
        id: "party",
        title: "Party & guests",
        theme: HomeTheme(
            id: "party",
            title: "House party",
            subtitle: "Dance floor & backyard BBQ",
            playlistKeywords: ["party", "dance", "bbq", "guests", "celebration", "grill", "house party", "get together", "hits"],
            genreKeywords: ["dance", "pop", "funk", "disco", "hip-hop"]
        )
    )
    static let windDown = HomeMoodSection(
        id: "wind-down",
        title: "Wind down",
        theme: HomeTheme(
            id: "wind-down",
            title: "Evening calm",
            subtitle: "Relax, unwind & bedtime",
            playlistKeywords: ["chill", "relax", "evening", "unwind", "bedtime", "sleep", "calm", "soft", "night", "nature"],
            genreKeywords: ["ambient", "acoustic", "classical", "new age", "folk"]
        )
    )

    static func all() -> [HomeMoodSection] {
        [dinner, french, italian, workFromHome, roadTrip, sundayMorning, party, windDown]
    }
}
