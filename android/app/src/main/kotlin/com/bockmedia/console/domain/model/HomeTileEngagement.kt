package com.bockmedia.console.domain.model

import android.content.Context
import org.json.JSONObject

data class TileEngagementEntry(
    val firstSeenMs: Long,
    val lastSelectedMs: Long? = null,
)

object HomeTileEngagement {
    const val STALE_DAYS = 4
    val STALE_MS: Long = STALE_DAYS * 24L * 60 * 60 * 1000

    private const val PREFS = "home_tile_engagement"
    private const val KEY = "tiles"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    internal var inMemoryStore: MutableMap<String, TileEngagementEntry>? = null
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = checkNotNull(appContext) {
        "HomeTileEngagement.init(context) must be called before use"
    }.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): MutableMap<String, TileEngagementEntry> = synchronized(lock) {
        inMemoryStore?.let { return@synchronized it }
        val raw = prefs().getString(KEY, null) ?: return@synchronized mutableMapOf()
        runCatching {
            val obj = JSONObject(raw)
            buildMap {
                for (key in obj.keys()) {
                    val entry = obj.getJSONObject(key)
                    put(
                        key,
                        TileEngagementEntry(
                            firstSeenMs = entry.getLong("firstSeenMs"),
                            lastSelectedMs = entry.optLong("lastSelectedMs", 0L).takeIf { it > 0L },
                        ),
                    )
                }
            }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun save(map: Map<String, TileEngagementEntry>) = synchronized(lock) {
        inMemoryStore?.let { store ->
            val snapshot = map.toMap()
            store.clear()
            store.putAll(snapshot)
            return@synchronized
        }
        val obj = JSONObject()
        for ((id, entry) in map) {
            obj.put(
                id,
                JSONObject()
                    .put("firstSeenMs", entry.firstSeenMs)
                    .put("lastSelectedMs", entry.lastSelectedMs ?: 0L),
            )
        }
        prefs().edit().putString(KEY, obj.toString()).apply()
    }

    private fun isActive(): Boolean = appContext != null || inMemoryStore != null

    fun noteCardsPresent(cardIds: Collection<String>) {
        if (!isActive()) return
        val now = System.currentTimeMillis()
        val map = load()
        var changed = false
        for (id in cardIds) {
            if (!map.containsKey(id)) {
                map[id] = TileEngagementEntry(firstSeenMs = now)
                changed = true
            }
        }
        if (changed) save(map)
    }

    fun recordSelection(cardId: String) {
        if (!isActive()) return
        val now = System.currentTimeMillis()
        val map = load()
        val existing = map[cardId]
        map[cardId] = if (existing != null) {
            existing.copy(lastSelectedMs = now)
        } else {
            TileEngagementEntry(firstSeenMs = now, lastSelectedMs = now)
        }
        save(map)
    }

    fun isStale(cardId: String, now: Long = System.currentTimeMillis()): Boolean {
        val entry = load()[cardId] ?: return false
        val anchor = entry.lastSelectedMs ?: entry.firstSeenMs
        return now - anchor >= STALE_MS
    }

    internal fun useInMemoryForTesting() {
        inMemoryStore = mutableMapOf()
    }

    internal fun resetForTesting() {
        synchronized(lock) {
            inMemoryStore?.clear()
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        }
    }

    internal fun putForTesting(cardId: String, entry: TileEngagementEntry) {
        val map = load()
        map[cardId] = entry
        save(map)
    }
}
