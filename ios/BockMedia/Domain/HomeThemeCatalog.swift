import Foundation

struct HomeTheme: Equatable {
    let id: String
    let title: String
    let subtitle: String
    let playlistKeywords: [String]
    let genreKeywords: [String]
}

enum HomeThemeCatalog {
    private static let french = HomeTheme(
        id: "french",
        title: "French Music",
        subtitle: "Chanson, pop & more",
        playlistKeywords: ["french", "français", "francais", "france", "chanson", "paris"],
        genreKeywords: ["french", "français", "francais", "chanson"]
    )
    private static let italian = HomeTheme(
        id: "italian",
        title: "Italian Music",
        subtitle: "Pop, opera & classics",
        playlistKeywords: ["italian", "italiano", "italia", "italy", "canzone"],
        genreKeywords: ["italian", "italiano", "italia"]
    )
    private static let german = HomeTheme(
        id: "german",
        title: "German Music",
        subtitle: "Schlager, rock & pop",
        playlistKeywords: ["german", "deutsch", "deutschland", "germany", "schlager"],
        genreKeywords: ["german", "deutsch", "schlager"]
    )
    private static let spanishLatin = HomeTheme(
        id: "spanish-latin",
        title: "Spanish & Latin",
        subtitle: "Pop, salsa & reggaeton",
        playlistKeywords: ["spanish", "español", "espanol", "latin", "latino", "latina", "salsa", "reggaeton", "mexican", "mexico"],
        genreKeywords: ["spanish", "español", "espanol", "latin", "latino", "salsa", "reggaeton"]
    )
    private static let portuguese = HomeTheme(
        id: "portuguese",
        title: "Portuguese & Brasil",
        subtitle: "MPB, samba & bossa",
        playlistKeywords: ["portuguese", "português", "portugues", "brasil", "brazil", "bossa", "samba", "mpb"],
        genreKeywords: ["portuguese", "português", "portugues", "brasil", "brazil", "bossa", "samba"]
    )
    private static let jazz = HomeTheme(
        id: "jazz",
        title: "Jazz",
        subtitle: "Swing, standards & fusion",
        playlistKeywords: ["jazz", "swing", "bebop", "blues jazz"],
        genreKeywords: ["jazz", "swing", "bebop"]
    )
    private static let classical = HomeTheme(
        id: "classical",
        title: "Classical",
        subtitle: "Orchestra, opera & piano",
        playlistKeywords: ["classical", "orchestra", "symphony", "opera", "baroque", "chamber"],
        genreKeywords: ["classical", "orchestra", "symphony", "opera", "baroque"]
    )
    private static let country = HomeTheme(
        id: "country",
        title: "Country",
        subtitle: "Nashville, bluegrass & roots",
        playlistKeywords: ["country", "bluegrass", "americana", "nashville", "honky"],
        genreKeywords: ["country", "bluegrass", "americana"]
    )
    private static let bluesSoul = HomeTheme(
        id: "blues-soul",
        title: "Blues & Soul",
        subtitle: "R&B, funk & soul",
        playlistKeywords: ["blues", "soul", "r&b", "rnb", "funk", "motown"],
        genreKeywords: ["blues", "soul", "r&b", "rnb", "funk", "motown"]
    )
    private static let reggae = HomeTheme(
        id: "reggae",
        title: "Reggae & Caribbean",
        subtitle: "Reggae, ska & dancehall",
        playlistKeywords: ["reggae", "ska", "dancehall", "caribbean", "dub"],
        genreKeywords: ["reggae", "ska", "dancehall", "caribbean"]
    )
    private static let celticFolk = HomeTheme(
        id: "celtic-folk",
        title: "Celtic & Folk",
        subtitle: "Irish, Scottish & acoustic",
        playlistKeywords: ["celtic", "irish", "scottish", "folk", "trad", "traditional"],
        genreKeywords: ["celtic", "irish", "folk", "traditional"]
    )
    private static let gospel = HomeTheme(
        id: "gospel",
        title: "Gospel & Hymns",
        subtitle: "Worship, hymns & spiritual",
        playlistKeywords: ["gospel", "hymn", "hymns", "worship", "spiritual", "christian", "lutheran"],
        genreKeywords: ["gospel", "hymn", "worship", "spiritual", "christian"]
    )
    private static let eighties = HomeTheme(
        id: "80s",
        title: "80s Hits",
        subtitle: "Synth-pop, rock & new wave",
        playlistKeywords: ["80s", "eighties", "1980", "'80s"],
        genreKeywords: ["80s", "eighties", "1980"]
    )
    private static let nineties = HomeTheme(
        id: "90s",
        title: "90s Hits",
        subtitle: "Grunge, pop & hip-hop",
        playlistKeywords: ["90s", "nineties", "1990", "'90s"],
        genreKeywords: ["90s", "nineties", "1990"]
    )
    private static let acoustic = HomeTheme(
        id: "acoustic",
        title: "Acoustic & Unplugged",
        subtitle: "Singer-songwriter & soft rock",
        playlistKeywords: ["acoustic", "unplugged", "singer-songwriter", "singer songwriter"],
        genreKeywords: ["acoustic", "unplugged", "folk"]
    )
    private static let electronic = HomeTheme(
        id: "electronic",
        title: "Electronic & Dance",
        subtitle: "EDM, house & techno",
        playlistKeywords: ["electronic", "edm", "dance", "techno", "house", "trance", "disco"],
        genreKeywords: ["electronic", "edm", "dance", "techno", "house", "trance"]
    )
    private static let hipHop = HomeTheme(
        id: "hip-hop",
        title: "Hip-Hop & Rap",
        subtitle: "Rap, trap & beats",
        playlistKeywords: ["hip-hop", "hip hop", "hiphop", "rap", "trap"],
        genreKeywords: ["hip-hop", "hip hop", "hiphop", "rap", "trap"]
    )
    private static let rockMetal = HomeTheme(
        id: "rock-metal",
        title: "Rock & Metal",
        subtitle: "Hard rock, punk & metal",
        playlistKeywords: ["rock", "metal", "hard rock", "punk", "alternative", "grunge"],
        genreKeywords: ["rock", "metal", "punk", "alternative", "grunge"]
    )
    private static let soundtracks = HomeTheme(
        id: "soundtracks",
        title: "Soundtracks",
        subtitle: "Film, TV & game scores",
        playlistKeywords: ["soundtrack", "score", "film", "movie", "tv", "video game", "ost"],
        genreKeywords: ["soundtrack", "score", "film", "movie"]
    )
    private static let kids = HomeTheme(
        id: "kids",
        title: "Kids & Family",
        subtitle: "Children's songs & sing-alongs",
        playlistKeywords: ["kids", "kid", "children", "family", "disney", "nursery", "sing-along"],
        genreKeywords: ["children", "kids", "family"]
    )
    private static let world = HomeTheme(
        id: "world",
        title: "World Music",
        subtitle: "Global sounds & traditions",
        playlistKeywords: ["world", "international", "global", "african", "asian", "middle eastern"],
        genreKeywords: ["world", "international", "african", "asian"]
    )

    private static let pinned: [HomeTheme] = [
        german, spanishLatin, jazz, classical, gospel,
    ]
    private static let rotating: [HomeTheme] = [
        portuguese, country, bluesSoul, reggae, celticFolk,
        eighties, nineties, acoustic, electronic, hipHop, rockMetal, soundtracks, kids, world,
    ]

    static func themesForDay(seed: UInt64) -> [HomeTheme] {
        let safeSeed = seed == 0 ? 0x4d595449 : seed
        var generator = SeededRandomNumberGenerator(seed: safeSeed)
        return pinned + rotating.shuffled(using: &generator)
    }
}
