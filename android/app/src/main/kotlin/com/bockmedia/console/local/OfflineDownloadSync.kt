package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.domain.model.PlayTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Registry bucket when no household profile is selected yet — downloads still run locally. */
internal const val OFFLINE_LOCAL_MEMBER_KEY = "__local__"

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
        val memberId = registryMemberId(context)
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

    /** Collection ids visible for the active profile (or local bucket before profile pick). */
    fun visibleCollectionIds(context: Context): Set<String> =
        collectionIdsForMember(context, registryMemberId(context))

    fun collectionIdsForMember(context: Context, memberId: String?): Set<String> {
        if (memberId.isNullOrBlank()) return emptySet()
        return loadMap(context)[memberId]?.map { it.id }?.toSet() ?: emptySet()
    }

    fun collectForMember(context: Context): List<OfflineDownloadRecord> =
        recordsForMember(context, ActiveProfileStore.activeMemberId(context))

    fun recordsForMember(context: Context, memberId: String?): List<OfflineDownloadRecord> {
        if (memberId.isNullOrBlank()) return emptyList()
        return loadMap(context)[memberId].orEmpty()
    }

    /** Attach on-disk collections not yet assigned to any profile (legacy / pre-registry). */
    fun claimOrphansForActiveProfile(context: Context) {
        val memberId = ActiveProfileStore.activeMemberId(context) ?: return
        val map = loadMap(context).toMutableMap()
        val assigned = map.values.flatten().map { it.id }.toSet()
        val orphans = OfflineDownloadStore(context).listManifests()
            .filter { it.id !in assigned }
            .map { it.toRecord() }
        val localPending = map.remove(OFFLINE_LOCAL_MEMBER_KEY).orEmpty()
            .filter { it.id !in assigned }
        val toClaim = mergeRecords(orphans + localPending)
        if (toClaim.isEmpty()) return
        val list = map[memberId]?.toMutableList() ?: mutableListOf()
        list.addAll(toClaim)
        map[memberId] = mergeRecords(list)
        saveMap(context, map)
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
            if (OfflineDownloadManager.statusFor(context, target)?.state == DownloadState.Downloading) continue
            // Partial or failed downloads require an explicit user retry.
            if (OfflineDownloadManager.statusFor(context, target)?.state == DownloadState.Failed) continue
            if (manifest != null && !store.isCollectionComplete(manifest)) continue
            OfflineDownloadManager.download(appContext, target)
        }
    }

    fun encode(records: List<OfflineDownloadRecord>): String =
        json.encodeToString(records)

    fun decode(raw: String?): List<OfflineDownloadRecord>? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<List<OfflineDownloadRecord>>(raw) }.getOrNull()
    }

    /** Clears registry prefs and decode cache (instrumented tests only). */
    internal fun resetRegistryForTests(context: Context) {
        cachedRaw = null
        cachedMap = emptyMap()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun registryMemberId(context: Context): String =
        ActiveProfileStore.activeMemberId(context) ?: OFFLINE_LOCAL_MEMBER_KEY

    private fun add(context: Context, record: OfflineDownloadRecord) {
        val memberId = registryMemberId(context)
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

    // Decode cache — loadMap is called from composition on every download progress
    // tick; re-parsing the JSON registry each time janks the main thread.
    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedMap: Map<String, List<OfflineDownloadRecord>> = emptyMap()

    private fun loadMap(context: Context): Map<String, List<OfflineDownloadRecord>> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return emptyMap()
        if (raw == cachedRaw) return cachedMap
        val parsed = runCatching {
            json.decodeFromString<Map<String, List<OfflineDownloadRecord>>>(raw)
        }.getOrDefault(emptyMap())
        cachedRaw = raw
        cachedMap = parsed
        return parsed
    }

    private fun saveMap(context: Context, map: Map<String, List<OfflineDownloadRecord>>) {
        val encoded = json.encodeToString(map)
        cachedRaw = encoded
        cachedMap = map
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encoded)
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
