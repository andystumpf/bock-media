package com.bockmedia.console.domain.model

import kotlin.random.Random

data class HomeTheme(
    val id: String,
    val title: String,
    val subtitle: String,
    val playlistKeywords: List<String>,
    val genreKeywords: List<String>,
)

object HomeThemeCatalog {
    private val french = HomeTheme(
        id = "french",
        title = "French Music",
        subtitle = "Chanson, pop & more",
        playlistKeywords = listOf("french", "français", "francais", "france", "chanson", "paris"),
        genreKeywords = listOf("french", "français", "francais", "chanson"),
    )
    private val italian = HomeTheme(
        id = "italian",
        title = "Italian Music",
        subtitle = "Pop, opera & classics",
        playlistKeywords = listOf("italian", "italiano", "italia", "italy", "canzone"),
        genreKeywords = listOf("italian", "italiano", "italia"),
    )
    private val german = HomeTheme(
        id = "german",
        title = "German Music",
        subtitle = "Schlager, rock & pop",
        playlistKeywords = listOf("german", "deutsch", "deutschland", "germany", "schlager"),
        genreKeywords = listOf("german", "deutsch", "schlager"),
    )
    private val spanishLatin = HomeTheme(
        id = "spanish-latin",
        title = "Spanish & Latin",
        subtitle = "Pop, salsa & reggaeton",
        playlistKeywords = listOf("spanish", "español", "espanol", "latin", "latino", "latina", "salsa", "reggaeton", "mexican", "mexico"),
        genreKeywords = listOf("spanish", "español", "espanol", "latin", "latino", "salsa", "reggaeton"),
    )
    private val portuguese = HomeTheme(
        id = "portuguese",
        title = "Portuguese & Brasil",
        subtitle = "MPB, samba & bossa",
        playlistKeywords = listOf("portuguese", "português", "portugues", "brasil", "brazil", "bossa", "samba", "mpb"),
        genreKeywords = listOf("portuguese", "português", "portugues", "brasil", "brazil", "bossa", "samba"),
    )
    private val jazz = HomeTheme(
        id = "jazz",
        title = "Jazz",
        subtitle = "Swing, standards & fusion",
        playlistKeywords = listOf("jazz", "swing", "bebop", "blues jazz"),
        genreKeywords = listOf("jazz", "swing", "bebop"),
    )
    private val classical = HomeTheme(
        id = "classical",
        title = "Classical",
        subtitle = "Orchestra, opera & piano",
        playlistKeywords = listOf("classical", "orchestra", "symphony", "opera", "baroque", "chamber"),
        genreKeywords = listOf("classical", "orchestra", "symphony", "opera", "baroque"),
    )
    private val country = HomeTheme(
        id = "country",
        title = "Country",
        subtitle = "Nashville, bluegrass & roots",
        playlistKeywords = listOf("country", "bluegrass", "americana", "nashville", "honky"),
        genreKeywords = listOf("country", "bluegrass", "americana"),
    )
    private val bluesSoul = HomeTheme(
        id = "blues-soul",
        title = "Blues & Soul",
        subtitle = "R&B, funk & soul",
        playlistKeywords = listOf("blues", "soul", "r&b", "rnb", "funk", "motown"),
        genreKeywords = listOf("blues", "soul", "r&b", "rnb", "funk", "motown"),
    )
    private val reggae = HomeTheme(
        id = "reggae",
        title = "Reggae & Caribbean",
        subtitle = "Reggae, ska & dancehall",
        playlistKeywords = listOf("reggae", "ska", "dancehall", "caribbean", "dub"),
        genreKeywords = listOf("reggae", "ska", "dancehall", "caribbean"),
    )
    private val celticFolk = HomeTheme(
        id = "celtic-folk",
        title = "Celtic & Folk",
        subtitle = "Irish, Scottish & acoustic",
        playlistKeywords = listOf("celtic", "irish", "scottish", "folk", "trad", "traditional"),
        genreKeywords = listOf("celtic", "irish", "folk", "traditional"),
    )
    private val gospel = HomeTheme(
        id = "gospel",
        title = "Gospel & Hymns",
        subtitle = "Worship, hymns & spiritual",
        playlistKeywords = listOf("gospel", "hymn", "hymns", "worship", "spiritual", "christian", "lutheran"),
        genreKeywords = listOf("gospel", "hymn", "worship", "spiritual", "christian"),
    )
    private val eighties = HomeTheme(
        id = "80s",
        title = "80s Hits",
        subtitle = "Synth-pop, rock & new wave",
        playlistKeywords = listOf("80s", "eighties", "1980", "'80s"),
        genreKeywords = listOf("80s", "eighties", "1980"),
    )
    private val nineties = HomeTheme(
        id = "90s",
        title = "90s Hits",
        subtitle = "Grunge, pop & hip-hop",
        playlistKeywords = listOf("90s", "nineties", "1990", "'90s"),
        genreKeywords = listOf("90s", "nineties", "1990"),
    )
    private val acoustic = HomeTheme(
        id = "acoustic",
        title = "Acoustic & Unplugged",
        subtitle = "Singer-songwriter & soft rock",
        playlistKeywords = listOf("acoustic", "unplugged", "singer-songwriter", "singer songwriter"),
        genreKeywords = listOf("acoustic", "unplugged", "folk"),
    )
    private val electronic = HomeTheme(
        id = "electronic",
        title = "Electronic & Dance",
        subtitle = "EDM, house & techno",
        playlistKeywords = listOf("electronic", "edm", "dance", "techno", "house", "trance", "disco"),
        genreKeywords = listOf("electronic", "edm", "dance", "techno", "house", "trance"),
    )
    private val hipHop = HomeTheme(
        id = "hip-hop",
        title = "Hip-Hop & Rap",
        subtitle = "Rap, trap & beats",
        playlistKeywords = listOf("hip-hop", "hip hop", "hiphop", "rap", "trap"),
        genreKeywords = listOf("hip-hop", "hip hop", "hiphop", "rap", "trap"),
    )
    private val rockMetal = HomeTheme(
        id = "rock-metal",
        title = "Rock & Metal",
        subtitle = "Hard rock, punk & metal",
        playlistKeywords = listOf("rock", "metal", "hard rock", "punk", "alternative", "grunge"),
        genreKeywords = listOf("rock", "metal", "punk", "alternative", "grunge"),
    )
    private val soundtracks = HomeTheme(
        id = "soundtracks",
        title = "Soundtracks",
        subtitle = "Film, TV & game scores",
        playlistKeywords = listOf("soundtrack", "score", "film", "movie", "tv", "video game", "ost"),
        genreKeywords = listOf("soundtrack", "score", "film", "movie"),
    )
    private val kids = HomeTheme(
        id = "kids",
        title = "Kids & Family",
        subtitle = "Children's songs & sing-alongs",
        playlistKeywords = listOf("kids", "kid", "children", "family", "disney", "nursery", "sing-along"),
        genreKeywords = listOf("children", "kids", "family"),
    )
    private val world = HomeTheme(
        id = "world",
        title = "World Music",
        subtitle = "Global sounds & traditions",
        playlistKeywords = listOf("world", "international", "global", "african", "asian", "middle eastern"),
        genreKeywords = listOf("world", "international", "african", "asian"),
    )

    private val pinned = listOf(german, spanishLatin, jazz, classical, gospel)
    private val rotating = listOf(
        portuguese, country, bluesSoul, reggae, celticFolk,
        eighties, nineties, acoustic, electronic, hipHop, rockMetal, soundtracks, kids, world,
    )

    fun themesForDay(seed: Long): List<HomeTheme> {
        val safeSeed = if (seed == 0L) 0x4d595449L else seed
        return pinned + rotating.shuffled(Random(safeSeed))
    }
}
