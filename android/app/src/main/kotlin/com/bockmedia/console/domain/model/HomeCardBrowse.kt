package com.bockmedia.console.domain.model

/** Where a home section tile should navigate on tap (browse — not play). */
object HomeCardBrowse {
    sealed class Destination {
        data class Playlist(val id: String) : Destination()
        data class Artist(val name: String) : Destination()
        data class Album(val name: String, val artist: String? = null) : Destination()
        data class Genre(val name: String) : Destination()
        data object Downloads : Destination()
        data object Search : Destination()
    }

    fun destination(card: HomeCard): Destination? {
        if (card.kind == HomeSectionKind.Offline) return Destination.Downloads
        if (card.kind == HomeSectionKind.BrowseGenres) return Destination.Genre(card.title)
        card.linkedPlaylistId()?.let { return Destination.Playlist(it) }
        // Synthetic "<genre> Mix" tiles seed playback from an artist, but tapping should
        // open the genre — not the artist page (whose header would be the artist name).
        mixGenre(card)?.let { return Destination.Genre(it) }
        return when (val target = card.playTarget) {
            is PlayTarget.Playlist -> Destination.Playlist(target.id)
            is PlayTarget.Artist -> Destination.Artist(target.name)
            is PlayTarget.Album -> Destination.Album(target.name, target.artist)
            is PlayTarget.Radio -> when (target.seedKind) {
                PlayTarget.RadioSeedKind.Artist -> Destination.Artist(target.name)
                PlayTarget.RadioSeedKind.Genre -> Destination.Genre(target.name)
                PlayTarget.RadioSeedKind.Song -> Destination.Search
            }
            is PlayTarget.Song -> Destination.Search
        }
    }

    /** Genre behind a synthetic "<genre> Mix" tile (artist-seeded, no real playlist). */
    private fun mixGenre(card: HomeCard): String? {
        if (card.kind != HomeSectionKind.TopMixes) return null
        if (card.linkedPlaylistId() != null) return null
        return when (val target = card.playTarget) {
            is PlayTarget.Artist -> HomeFeedRules.mixGenreLabel(card.title)
            is PlayTarget.Radio ->
                if (target.seedKind == PlayTarget.RadioSeedKind.Genre) HomeFeedRules.mixGenreLabel(card.title) else null
            else -> null
        }
    }
}
