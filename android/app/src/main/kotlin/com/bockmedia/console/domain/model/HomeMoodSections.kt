package com.bockmedia.console.domain.model

data class HomeMoodSection(
    val id: String,
    val title: String,
    val theme: HomeTheme,
)

/** Curated lifestyle rows — each becomes its own home section. */
object HomeMoodSections {
    val dinner = HomeMoodSection(
        id = "dinner",
        title = "Dinner & entertaining",
        theme = HomeTheme(
            id = "dinner",
            title = "Dinner playlist",
            subtitle = "Cooking, hosting & table music",
            playlistKeywords = listOf(
                "dinner", "cooking", "kitchen", "entertaining", "cocktail", "wine",
                "supper", "table", "host", "feast",
            ),
            genreKeywords = listOf("dinner", "jazz", "lounge", "easy listening"),
        ),
    )
    val french = HomeMoodSection(
        id = "french",
        title = "French music",
        theme = HomeTheme(
            id = "french",
            title = "French favorites",
            subtitle = "Chanson, pop & café culture",
            playlistKeywords = listOf("french", "français", "francais", "france", "chanson", "paris"),
            genreKeywords = listOf("french", "français", "francais", "chanson"),
        ),
    )
    val italian = HomeMoodSection(
        id = "italian",
        title = "Italian music",
        theme = HomeTheme(
            id = "italian",
            title = "Italian classics",
            subtitle = "Pop, opera & la dolce vita",
            playlistKeywords = listOf("italian", "italiano", "italia", "italy", "canzone", "rome"),
            genreKeywords = listOf("italian", "italiano", "italia"),
        ),
    )
    val yachtRock = HomeMoodSection(
        id = "yacht-rock",
        title = "Yacht Rock",
        theme = HomeTheme(
            id = "yacht-rock",
            title = "Yacht Rock",
            subtitle = "Smooth sailing & soft rock",
            playlistKeywords = listOf("yacht"),
            genreKeywords = listOf("yacht rock", "soft rock"),
        ),
    )
    val workFromHome = HomeMoodSection(
        id = "work-from-home",
        title = "Work from home",
        theme = HomeTheme(
            id = "work-from-home",
            title = "Focus flow",
            subtitle = "Deep work & concentration",
            playlistKeywords = listOf(
                "work", "focus", "wfh", "concentration", "office", "productivity",
                "coding", "study", "deep work", "instrumental",
            ),
            genreKeywords = listOf("ambient", "classical", "electronic", "instrumental"),
        ),
    )
    val roadTrip = HomeMoodSection(
        id = "road-trip",
        title = "Road trip",
        theme = HomeTheme(
            id = "road-trip",
            title = "Highway mix",
            subtitle = "Driving, windows down & open road",
            playlistKeywords = listOf(
                "road trip", "roadtrip", "driving", "highway", "travel", "car",
                "journey", "on the road", "windows down",
            ),
            genreKeywords = listOf("rock", "country", "pop", "classic rock"),
        ),
    )
    val sundayMorning = HomeMoodSection(
        id = "sunday-morning",
        title = "Sunday morning",
        theme = HomeTheme(
            id = "sunday-morning",
            title = "Easy Sunday",
            subtitle = "Brunch, coffee & slow starts",
            playlistKeywords = listOf(
                "sunday", "morning", "brunch", "coffee", "easy", "wake", "weekend",
                "sunrise", "lazy",
            ),
            genreKeywords = listOf("folk", "acoustic", "jazz", "easy listening", "gospel"),
        ),
    )
    val party = HomeMoodSection(
        id = "party",
        title = "Party & guests",
        theme = HomeTheme(
            id = "party",
            title = "House party",
            subtitle = "Dance floor & backyard BBQ",
            playlistKeywords = listOf(
                "party", "dance", "bbq", "guests", "celebration", "grill",
                "house party", "get together", "hits",
            ),
            genreKeywords = listOf("dance", "pop", "funk", "disco", "hip-hop"),
        ),
    )
    val windDown = HomeMoodSection(
        id = "wind-down",
        title = "Wind down",
        theme = HomeTheme(
            id = "wind-down",
            title = "Evening calm",
            subtitle = "Relax, unwind & bedtime",
            playlistKeywords = listOf(
                "chill", "relax", "evening", "unwind", "bedtime", "sleep",
                "calm", "soft", "night", "nature",
            ),
            genreKeywords = listOf("ambient", "acoustic", "classical", "new age", "folk"),
        ),
    )

    fun all(): List<HomeMoodSection> = listOf(
        dinner,
        french,
        italian,
        yachtRock,
        workFromHome,
        roadTrip,
        sundayMorning,
        party,
        windDown,
    )
}
