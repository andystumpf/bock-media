package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.PlaylistSummary

data class HomeSectionPin(
    val sectionId: String,
    val playlistId: String,
    val playlistName: String,
    val pinnedAtMs: Long = System.currentTimeMillis(),
)

object HomePinTargets {
    data class Target(val sectionId: String, val title: String)

    fun pinEligible(): List<Target> = buildList {
        add(Target("recent-playlists", "Recent playlists"))
        add(Target("top-mixes", "Your top mixes"))
        add(Target("daily-mixes", "New daily mixes"))
        add(Target("discover", "Discover"))
        add(Target("explore-themes", "Explore genres & worlds"))
        add(Target("more-playlists", "More playlists"))
        for (mood in HomeMoodSections.all()) {
            add(Target("mood-${mood.id}", mood.title))
        }
        for (decade in HomeDecadeSections.all()) {
            add(Target("decade-${decade.id}", decade.title))
        }
    }

    fun titleFor(sectionId: String): String =
        pinEligible().firstOrNull { it.sectionId == sectionId }?.title ?: sectionId

    /** Best home row for a Sonic Sage / Mix Muse playlist name. */
    fun suggestSectionId(playlistName: String): String {
        val text = playlistName.lowercase()
        var bestId: String? = null
        var bestScore = 0
        for (decade in HomeDecadeSections.all()) {
            if (HomeFeedRules.playlistMatchesDecade(text, decade.id)) {
                return "decade-${decade.id}"
            }
        }
        for (mood in HomeMoodSections.all()) {
            var score = 0
            for (kw in mood.theme.playlistKeywords) {
                if (kw in text) score += 2
            }
            for (kw in mood.theme.genreKeywords) {
                if (kw in text) score += 1
            }
            if (score > bestScore) {
                bestScore = score
                bestId = "mood-${mood.id}"
            }
        }
        if (bestId != null && bestScore > 0) return bestId
        if ("morning" in text || "weekday" in text || "brunch" in text) return "mood-sunday-morning"
        if ("calm" in text || "relax" in text || "wind" in text) return "mood-wind-down"
        if ("party" in text || "dance" in text) return "mood-party"
        if ("road" in text || "drive" in text || "trip" in text) return "mood-road-trip"
        return "recent-playlists"
    }
}

object HomeSectionPinsApplier {
    /** Server-saved baseline pins first, then user pins (no duplicates). */
    fun mergePins(server: List<HomeSectionPin>, local: List<HomeSectionPin>): List<HomeSectionPin> {
        if (server.isEmpty()) return local
        val seen = server.map { it.sectionId to it.playlistId }.toMutableSet()
        val out = server.toMutableList()
        for (pin in local) {
            val key = pin.sectionId to pin.playlistId
            if (seen.add(key)) out.add(pin)
        }
        return out
    }

    fun apply(feed: HomeFeed, pins: List<HomeSectionPin>, playlists: List<PlaylistSummary>): HomeFeed {
        if (pins.isEmpty()) return feed
        val bySection = pins.groupBy { it.sectionId }
        val playlistById = playlists.associateBy { it.id }
        val sections = feed.sections.map { section ->
            val sectionPins = bySection[section.id].orEmpty()
                .sortedByDescending { it.pinnedAtMs }
            if (sectionPins.isEmpty()) return@map section
            val pinCards = sectionPins.mapNotNull { pin ->
                val pl = playlistById[pin.playlistId] ?: PlaylistSummary(
                    id = pin.playlistId,
                    name = pin.playlistName,
                    tracks = 0,
                )
                HomeCard(
                    id = "pl-${pl.id}",
                    title = pl.name,
                    subtitle = if (pl.tracks > 0) "${pl.tracks} tracks · Pinned" else "Pinned for you",
                    artPath = pl.artPath,
                    playlistId = pl.id,
                    playTarget = PlayTarget.Playlist(pl.id, pl.name),
                    kind = section.kind,
                )
            }
            if (pinCards.isEmpty()) return@map section
            val rest = section.cards.filter { card ->
                pinCards.none { pin -> pin.playlistId == card.playlistId }
            }
            section.copy(cards = pinCards + rest)
        }
        return HomeFeed(sections)
    }
}
