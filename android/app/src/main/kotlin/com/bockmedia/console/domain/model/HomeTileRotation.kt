package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.PlaylistSummary

object HomeTileRotation {
    fun apply(feed: HomeFeed, input: HomeFeedInput, now: Long = System.currentTimeMillis()): HomeFeed {
        val cardIds = feed.sections.flatMap { it.cards }.map { it.id }
        HomeTileEngagement.noteCardsPresent(cardIds)

        val usedPlaylistIds = mutableSetOf<String>()
        val usedCardIds = mutableSetOf<String>()
        val usedPlaylistNames = mutableSetOf<String>()
        for (card in feed.sections.flatMap { it.cards }) {
            usedCardIds.add(card.id)
            card.playlistId?.let { usedPlaylistIds.add(it) }
            usedPlaylistNames.add(card.title.lowercase())
        }

        var rotationIndex = 0
        val sections = feed.sections.map { section ->
            if (!isRotatable(section.kind)) return@map section
            val cards = section.cards.map { card ->
                if (!HomeTileEngagement.isStale(card.id, now)) return@map card
                val replacement = findReplacement(
                    kind = section.kind,
                    stale = card,
                    input = input,
                    usedPlaylistIds = usedPlaylistIds,
                    usedCardIds = usedCardIds,
                    usedPlaylistNames = usedPlaylistNames,
                    rotationIndex = rotationIndex++,
                )
                if (replacement == null) return@map card
                usedCardIds.add(replacement.id)
                replacement.playlistId?.let { usedPlaylistIds.add(it) }
                usedPlaylistNames.add(replacement.title.lowercase())
                HomeTileEngagement.noteCardsPresent(listOf(replacement.id))
                replacement
            }
            section.copy(cards = cards)
        }
        return feed.copy(sections = sections)
    }

    private fun isRotatable(kind: HomeSectionKind): Boolean = when (kind) {
        HomeSectionKind.Favorites, HomeSectionKind.Offline -> false
        else -> true
    }

    private fun findReplacement(
        kind: HomeSectionKind,
        stale: HomeCard,
        input: HomeFeedInput,
        usedPlaylistIds: Set<String>,
        usedCardIds: Set<String>,
        usedPlaylistNames: Set<String>,
        rotationIndex: Int,
    ): HomeCard? {
        val seed = input.shuffleSeed + rotationIndex * 17L + stale.id.hashCode()
        val pool = HomeFeedRules.shuffledBrowsablePlaylists(input.allPlaylists, seed)
            .filter { pl ->
                pl.tracks > 0 &&
                    pl.id !in usedPlaylistIds &&
                    pl.name.lowercase() !in usedPlaylistNames &&
                    !HomeFeedRules.isSpecialHomePlaylistName(pl.name)
            }
        val playlist = pool.firstOrNull() ?: return null
        val cardId = "pl-${playlist.id}"
        if (cardId in usedCardIds) return null
        return HomeCard(
            id = cardId,
            title = playlist.name,
            subtitle = subtitleFor(kind, playlist),
            artPath = null,
            playlistId = playlist.id,
            playTarget = PlayTarget.Playlist(playlist.id, playlist.name),
            kind = kind,
        )
    }

    private fun subtitleFor(kind: HomeSectionKind, playlist: PlaylistSummary): String = when (kind) {
        HomeSectionKind.JumpBackIn -> "${playlist.tracks} tracks · Suggested for you"
        HomeSectionKind.TopMixes, HomeSectionKind.DailyMixes -> "Suggested mix"
        HomeSectionKind.Radio -> "From your library"
        HomeSectionKind.Discover -> "${playlist.tracks} tracks · Discover"
        HomeSectionKind.RecentPlaylists -> "${playlist.tracks} tracks · Suggested for you"
        else -> "${playlist.tracks} tracks"
    }
}
