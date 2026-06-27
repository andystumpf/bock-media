package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.domain.model.PlayTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Server-synced registry of offline collections per household profile. */
@Serializable
data class OfflineDownloadRecord(
    val id: String,
    val title: String,
    val kind: String = "playlist",
    val sourcePlaylistId: String? = null,
)

object OfflineDownloadSync {
    private const val PREFS = "offline_download_registry"
    private const val KEY = "by_member"
    private val json = Json { ignoreUnknownKeys = true }

    fun register(context: Context, target: PlayTarget) {
        add(context, target.toOfflineDownloadRecord())
    }

    fun remove(context: Context, collectionId: String) {
        val memberId = ActiveProfileStore.activeMemberId(context) ?: return
        val map = loadMap(context).toMutableMap()
        val list = map[memberId]?.toMutableList() ?: return
        if (list.removeAll { it.id == collectionId }) {
            map[memberId] = list
            saveMap(context, map)
        }
    }

    /** Records for the active profile (server source of truth after pull). */
    fun recordsForActiveProfile(context: Context): List<OfflineDownloadRecord> {
        val memberId = ActiveProfileStore.activeMemberId(context) ?: return emptyList()
        return loadMap(context)[memberId].orEmpty()
    }

    fun visibleCollectionIds(context: Context): Set<String>? {
        val memberId = ActiveProfileStore.activeMemberId(context) ?: return null
        val ids = loadMap(context)[memberId]?.map { it.id }.orEmpty()
        return ids.toSet()
    }

    fun collectForMember(context: Context): List<OfflineDownloadRecord> {
        val memberId = ActiveProfileStore.activeMemberId(context)
        val fromRegistry = if (memberId.isNullOrBlank()) emptyList() else loadMap(context)[memberId].orEmpty()
        val fromDisk = OfflineDownloadStore(context).listManifests().map { it.toRecord() }
        return mergeRecords(fromRegistry + fromDisk)
    }

    fun applyRemote(context: Context, records: List<OfflineDownloadRecord>) {
        val memberId = ActiveProfileStore.activeMemberId(context)
        if (!memberId.isNullOrBlank()) {
            val map = loadMap(context).toMutableMap()
            map[memberId] = mergeRecords(records)
            saveMap(context, map)
        }
        restoreMissing(context, records)
    }

    fun restoreMissing(context: Context, records: List<OfflineDownloadRecord>) {
        if (records.isEmpty()) return
        val appContext = context.applicationContext
        val store = OfflineDownloadStore(appContext)
        OfflineDownloadManager.refresh(appContext)
        for (record in records) {
            val target = record.toPlayTarget()
            val manifest = store.readManifest(record.id)
            if (manifest != null && store.isCollectionComplete(manifest)) continue
            if (OfflineDownloadManager.statusFor(target)?.state == DownloadState.Downloading) continue
            OfflineDownloadManager.download(appContext, target)
        }
    }

    fun encode(records: List<OfflineDownloadRecord>): String =
        json.encodeToString(records)

    fun decode(raw: String?): List<OfflineDownloadRecord>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<List<OfflineDownloadRecord>>(raw) }.getOrNull()
    }

    private fun add(context: Context, record: OfflineDownloadRecord) {
        val memberId = ActiveProfileStore.activeMemberId(context) ?: return
        val map = loadMap(context).toMutableMap()
        val list = map[memberId]?.toMutableList() ?: mutableListOf()
        list.removeAll { it.id == record.id }
        list.add(record)
        map[memberId] = list
        saveMap(context, map)
    }

    private fun mergeRecords(records: List<OfflineDownloadRecord>): List<OfflineDownloadRecord> {
        val out = linkedMapOf<String, OfflineDownloadRecord>()
        records.forEach { out[it.id] = it }
        return out.values.toList()
    }

    private fun loadMap(context: Context): Map<String, List<OfflineDownloadRecord>> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, List<OfflineDownloadRecord>>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun saveMap(context: Context, map: Map<String, List<OfflineDownloadRecord>>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(map))
            .apply()
    }
}

fun PlayTarget.toOfflineDownloadRecord(): OfflineDownloadRecord = OfflineDownloadRecord(
    id = downloadId(),
    title = label,
    kind = downloadKindLabel().lowercase(),
    sourcePlaylistId = (this as? PlayTarget.Playlist)?.id,
)

private fun OfflineDownloadRecord.toPlayTarget(): PlayTarget = when (kind) {
    "artist" -> PlayTarget.Artist(title)
    "album" -> PlayTarget.Album(title)
    "song" -> PlayTarget.Song(path = "", title = title)
    "mix", "radio" -> PlayTarget.Radio(title, PlayTarget.RadioSeedKind.Artist, title)
    else -> {
        val pid = sourcePlaylistId ?: id.removePrefix("pl-")
        PlayTarget.Playlist(pid, title)
    }
}

fun OfflineCollectionManifest.toRecord(): OfflineDownloadRecord = OfflineDownloadRecord(
    id = id,
    title = title,
    kind = kind,
    sourcePlaylistId = sourcePlaylistId,
)
