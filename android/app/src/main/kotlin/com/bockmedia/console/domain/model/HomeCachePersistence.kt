package com.bockmedia.console.domain.model

import android.content.Context
import com.bockmedia.console.data.api.bockJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Disk persistence for the home feed and its artwork lookups. Keeping a snapshot
 * on disk lets a cold launch paint the last-known home screen instantly while a
 * fresh copy loads in the background — instead of rebuilding from ~6 network
 * calls and re-resolving every cover every time.
 */
object HomeCachePersistence {
    private const val FILE_NAME = "home_cache.json"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    @Serializable
    private data class PlayTargetDto(
        val type: String,
        val id: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val path: String? = null,
        val title: String? = null,
        val displayTitle: String? = null,
        val seedKind: String? = null,
    )

    @Serializable
    private data class CardDto(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val artPath: String? = null,
        val playlistId: String? = null,
        val kind: String,
        val playTarget: PlayTargetDto,
    )

    @Serializable
    private data class SectionDto(
        val id: String,
        val title: String,
        val kind: String,
        val cards: List<CardDto>,
    )

    @Serializable
    private data class SnapshotDto(
        val savedAtMs: Long,
        val sections: List<SectionDto>,
        val cardUrls: Map<String, String> = emptyMap(),
        val cardMediaPaths: Map<String, String> = emptyMap(),
        val playlistPaths: Map<String, String> = emptyMap(),
    )

    data class Snapshot(
        val feed: HomeFeed,
        val cardMediaPaths: Map<String, String>,
        val playlistPaths: Map<String, String>,
    )

    /** Serializes off the main thread and writes atomically (temp + rename). */
    suspend fun save(
        context: Context,
        feed: HomeFeed,
        cardMediaPaths: Map<String, String>,
        playlistPaths: Map<String, String>,
    ) {
        if (feed.sections.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val dto = SnapshotDto(
                    savedAtMs = System.currentTimeMillis(),
                    sections = feed.sections.map { it.toDto() },
                    cardMediaPaths = cardMediaPaths,
                    playlistPaths = playlistPaths,
                )
                val json = bockJson.encodeToString(dto)
                val target = file(context)
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
        }
    }

    suspend fun load(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(context)
            if (!f.exists()) return@runCatching null
            val dto = bockJson.decodeFromString<SnapshotDto>(f.readText())
            if (System.currentTimeMillis() - dto.savedAtMs > MAX_AGE_MS) return@runCatching null
            val sections = dto.sections.mapNotNull { it.toModel() }
            if (sections.isEmpty()) return@runCatching null
            val cardPaths = dto.cardMediaPaths.ifEmpty {
                dto.cardUrls.mapNotNull { (id, url) ->
                    ArtworkPaths.extractMediaPath(url)?.let { id to it }
                }.toMap()
            }
            Snapshot(HomeFeed(sections), cardPaths, dto.playlistPaths)
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun HomeSection.toDto() = SectionDto(
        id = id,
        title = title,
        kind = kind.name,
        cards = cards.map { it.toDto() },
    )

    private fun HomeCard.toDto() = CardDto(
        id = id,
        title = title,
        subtitle = subtitle,
        artPath = artPath,
        playlistId = playlistId,
        kind = kind.name,
        playTarget = playTarget.toDto(),
    )

    private fun PlayTarget.toDto(): PlayTargetDto = when (this) {
        is PlayTarget.Playlist -> PlayTargetDto("playlist", id = id, name = name)
        is PlayTarget.Artist -> PlayTargetDto("artist", name = name)
        is PlayTarget.Album -> PlayTargetDto("album", name = name, artist = artist)
        is PlayTarget.Song -> PlayTargetDto("song", path = path, title = title)
        is PlayTarget.Radio -> PlayTargetDto(
            "radio",
            displayTitle = displayTitle,
            name = name,
            path = path,
            seedKind = seedKind.name,
        )
    }

    private fun SectionDto.toModel(): HomeSection? {
        val sectionKind = runCatching { HomeSectionKind.valueOf(kind) }.getOrNull() ?: return null
        val modelCards = cards.mapNotNull { it.toModel() }
        if (modelCards.isEmpty()) return null
        return HomeSection(id, title, sectionKind, modelCards)
    }

    private fun CardDto.toModel(): HomeCard? {
        val cardKind = runCatching { HomeSectionKind.valueOf(kind) }.getOrNull() ?: return null
        val target = playTarget.toModel() ?: return null
        return HomeCard(
            id = id,
            title = title,
            subtitle = subtitle,
            artPath = artPath,
            playlistId = playlistId,
            playTarget = target,
            kind = cardKind,
        )
    }

    private fun PlayTargetDto.toModel(): PlayTarget? = when (type) {
        "playlist" -> PlayTarget.Playlist(id.orEmpty(), name.orEmpty())
        "artist" -> name?.let { PlayTarget.Artist(it) }
        "album" -> name?.let { PlayTarget.Album(it, artist) }
        "song" -> path?.let { PlayTarget.Song(it, title ?: name ?: "Track") }
        "radio" -> {
            val seed = runCatching {
                PlayTarget.RadioSeedKind.valueOf(seedKind ?: "Artist")
            }.getOrDefault(PlayTarget.RadioSeedKind.Artist)
            PlayTarget.Radio(displayTitle ?: name.orEmpty(), seed, name.orEmpty(), path)
        }
        else -> null
    }
}
