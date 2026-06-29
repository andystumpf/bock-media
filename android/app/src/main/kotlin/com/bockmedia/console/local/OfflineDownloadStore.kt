package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlayTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Unique on-disk name for a library path; includes a path hash so same basename in different folders cannot collide. */
internal fun offlineTrackFileName(path: String, index: Int): String {
    val base = path.substringAfterLast('/').substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(60)
        .ifBlank { "track" }
    val ext = path.substringAfterLast('.', "mp3").take(8)
    val pathTag = kotlin.math.abs(path.hashCode()).toUInt().toString(16).padStart(8, '0')
    return "${index.toString().padStart(4, '0')}_${pathTag}_$base.$ext"
}

internal fun uniqueOfflineFileName(path: String, startIndex: Int, used: MutableSet<String>): String {
    var idx = startIndex
    while (true) {
        val candidate = offlineTrackFileName(path, idx)
        if (candidate !in used) {
            used.add(candidate)
            return candidate
        }
        idx++
    }
}

@Serializable
data class OfflineTrackEntry(
    val path: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val fileName: String,
)

@Serializable
data class OfflineCollectionManifest(
    val id: String,
    val title: String,
    val kind: String = "playlist",
    val sourcePlaylistId: String? = null,
    val coverArtPath: String? = null,
    val lastSyncedAtMs: Long = 0L,
    val downloadedAtMs: Long = System.currentTimeMillis(),
    val tracks: List<OfflineTrackEntry> = emptyList(),
) {
    /** Legacy playlists saved before collections used `playlistId` as folder name. */
    val legacyPlaylistId: String? get() = sourcePlaylistId ?: id.removePrefix("pl-").takeIf { id.startsWith("pl-") }
}

enum class DownloadState { Idle, Downloading, Complete, Failed }

data class OfflineCollectionStatus(
    val manifest: OfflineCollectionManifest,
    val state: DownloadState,
    val progress: Float = 0f,
    val error: String? = null,
)

class OfflineDownloadStore(context: Context) {
    private val appContext = context.applicationContext
    private val collectionsRoot = File(appContext.filesDir, "offline/collections")
    private val legacyRoot = File(appContext.filesDir, "offline/playlists")
    private val json = Json { ignoreUnknownKeys = true }

    fun collectionDir(id: String): File = File(collectionsRoot, id)

    fun trackFile(collectionId: String, fileName: String): File = File(collectionDir(collectionId), fileName)

    fun manifestFile(collectionId: String) = File(collectionDir(collectionId), "manifest.json")

    fun listManifests(): List<OfflineCollectionManifest> {
        val out = mutableListOf<OfflineCollectionManifest>()
        out += readAllFrom(collectionsRoot)
        out += readAllFrom(legacyRoot).map { legacy ->
            if (legacy.id.startsWith("pl-")) legacy else legacy.copy(id = "pl-${legacy.id}", sourcePlaylistId = legacy.id)
        }.filter { m -> out.none { it.id == m.id } }
        return out.sortedByDescending { it.downloadedAtMs }
    }

    private fun readAllFrom(root: File): List<OfflineCollectionManifest> {
        if (!root.exists()) return emptyList()
        return root.listFiles()?.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            readManifest(dir.name, root) ?: runCatching {
                readLegacyManifest(dir.name, root)?.let { legacy ->
                    OfflineCollectionManifest(
                        id = if (legacy.playlistId.startsWith("pl-")) legacy.playlistId else "pl-${legacy.playlistId}",
                        title = legacy.playlistName,
                        kind = "playlist",
                        sourcePlaylistId = legacy.playlistId,
                        downloadedAtMs = legacy.downloadedAtMs,
                        tracks = legacy.tracks,
                    )
                }
            }.getOrNull()
        } ?: emptyList()
    }

    fun readManifest(collectionId: String, root: File = collectionsRoot): OfflineCollectionManifest? {
        val file = File(root, "$collectionId/manifest.json")
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<OfflineCollectionManifest>(file.readText())
        }.getOrNull()
    }

    private fun readLegacyManifest(id: String, root: File): LegacyManifest? {
        val file = File(root, "$id/manifest.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<LegacyManifest>(file.readText()) }.getOrNull()
    }

    fun saveManifest(manifest: OfflineCollectionManifest) {
        val dir = collectionDir(manifest.id)
        dir.mkdirs()
        manifestFile(manifest.id).writeText(json.encodeToString(manifest))
    }

    fun deleteCollection(collectionId: String) {
        collectionDir(collectionId).deleteRecursively()
        File(legacyRoot, collectionId.removePrefix("pl-")).deleteRecursively()
        File(legacyRoot, collectionId).deleteRecursively()
    }

    fun localFileFor(path: String, collectionId: String? = null): File? {
        val manifests = manifestsForLookup(collectionId)
        for (manifest in manifests) {
            val entry = manifest.tracks.find { it.path == path } ?: continue
            resolveTrackFile(manifest, entry)?.let { return it }
        }
        return null
    }

    fun localTracksForManifest(manifest: OfflineCollectionManifest): List<LocalTrack> =
        manifest.tracks.mapNotNull { entry ->
            val file = resolveTrackFile(manifest, entry) ?: return@mapNotNull null
            LocalTrack(
                path = entry.path,
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                localFile = file,
            )
        }

    internal fun resolveTrackFile(manifest: OfflineCollectionManifest, entry: OfflineTrackEntry): File? {
        val file = trackFile(manifest.id, entry.fileName)
        if (file.exists() && file.length() > 0) return file
        val legacyId = manifest.legacyPlaylistId ?: manifest.id.removePrefix("pl-")
        val legacy = File(legacyRoot, "$legacyId/${entry.fileName}")
        if (legacy.exists() && legacy.length() > 0) return legacy
        return null
    }

    private fun manifestsForLookup(collectionId: String?): List<OfflineCollectionManifest> {
        if (collectionId == null) return listManifests()
        val ids = linkedSetOf(collectionId)
        if (collectionId.startsWith("pl-")) {
            ids += collectionId.removePrefix("pl-")
        } else {
            ids += "pl-$collectionId"
        }
        val out = mutableListOf<OfflineCollectionManifest>()
        for (id in ids) {
            readManifest(id)?.let { out += it }
            readManifest(id, legacyRoot)?.let { m -> if (out.none { it.id == m.id }) out += m }
        }
        return out
    }

    fun bytesOnDisk(): Long = dirSize(collectionsRoot) + dirSize(legacyRoot)

    fun collectionBytesOnDisk(collectionId: String): Long {
        var total = dirSize(collectionDir(collectionId))
        total += dirSize(File(legacyRoot, collectionId.removePrefix("pl-")))
        total += dirSize(File(legacyRoot, collectionId))
        return total
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun safeFileName(path: String, index: Int): String = offlineTrackFileName(path, index)

    fun completionProgress(manifest: OfflineCollectionManifest): Float {
        if (manifest.tracks.isEmpty()) return 0f
        val done = manifest.tracks.count { entry -> resolveTrackFile(manifest, entry) != null }
        return done.toFloat() / manifest.tracks.size
    }

    fun isCollectionComplete(manifest: OfflineCollectionManifest): Boolean =
        manifest.tracks.isNotEmpty() && manifest.tracks.all { resolveTrackFile(manifest, it) != null }

    fun mergeTrackEntries(
        existing: OfflineCollectionManifest?,
        resolved: List<com.bockmedia.console.domain.model.LocalTrack>,
        collectionId: String,
    ): List<OfflineTrackEntry> {
        val byPath = linkedMapOf<String, OfflineTrackEntry>()
        val usedNames = mutableSetOf<String>()
        existing?.tracks?.forEach {
            val fileName = if (it.fileName in usedNames) {
                uniqueOfflineFileName(it.path, usedNames.size, usedNames)
            } else {
                usedNames.add(it.fileName)
                it.fileName
            }
            byPath[it.path] = it.copy(fileName = fileName)
        }
        var seq = existing?.tracks?.size ?: 0
        resolved.forEach { track ->
            if (track.path.isBlank()) return@forEach
            val prior = byPath[track.path]
            val fileName = prior?.fileName ?: uniqueOfflineFileName(track.path, seq, usedNames).also { seq++ }
            byPath[track.path] = OfflineTrackEntry(
                path = track.path,
                title = track.title,
                artist = track.artist,
                album = track.album,
                fileName = fileName,
            )
        }
        return byPath.values.toList()
    }

    fun pruneOrphanFiles(collectionId: String, tracks: List<OfflineTrackEntry>) {
        val dir = collectionDir(collectionId)
        if (!dir.exists()) return
        val keep = tracks.map { it.fileName }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name != "manifest.json" && file.name !in keep) {
                file.delete()
            }
        }
    }
}

@Serializable
private data class LegacyManifest(
    val playlistId: String,
    val playlistName: String,
    val downloadedAtMs: Long = System.currentTimeMillis(),
    val tracks: List<OfflineTrackEntry> = emptyList(),
)

fun OfflineCollectionManifest.toPlayTarget(): PlayTarget {
    sourcePlaylistId?.let { id ->
        if (kind == "playlist" || id.isNotBlank()) {
            return PlayTarget.Playlist(id, title)
        }
    }
    return when (kind) {
        "artist" -> PlayTarget.Artist(title)
        "album" -> PlayTarget.Album(title)
        "song" -> tracks.firstOrNull()?.let { PlayTarget.Song(it.path, it.title) } ?: PlayTarget.Playlist(id, title)
        "mix", "radio" -> PlayTarget.Radio(title, PlayTarget.RadioSeedKind.Artist, title)
        else -> PlayTarget.Playlist(legacyPlaylistId ?: id.removePrefix("pl-"), title)
    }
}

fun formatOfflineBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${bytes / 1024} KB"
    if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    return "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

fun formatDownloadDate(ms: Long): String {
    if (ms <= 0) return "Unknown"
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
}
