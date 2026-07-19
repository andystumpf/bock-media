package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.HomeSectionPin
import org.json.JSONArray
import org.json.JSONObject

object HomeSectionPinsStore {
    private const val PREFS = "home_section_pins"
    private const val KEY = "pins"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    internal var inMemoryPins: MutableList<HomeSectionPin>? = null
    private val lock = Any()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs() = checkNotNull(appContext) {
        "HomeSectionPinsStore.init(context) must be called before use"
    }.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<HomeSectionPin> = synchronized(lock) {
        inMemoryPins?.toList() ?: run {
            val raw = prefs().getString(KEY, null) ?: return@synchronized emptyList()
            decode(raw)
        }
    }

    fun pin(context: Context, sectionId: String, playlistId: String, playlistName: String) {
        val now = System.currentTimeMillis()
        val updated = load()
            .filterNot { it.sectionId == sectionId && it.playlistId == playlistId } +
            HomeSectionPin(sectionId, playlistId, playlistName, now)
        save(updated)
        HomeFeedCache.invalidate()
        ClientPrefsSync.schedulePush(context.applicationContext)
    }

    fun unpinnedSection(context: Context, sectionId: String, playlistId: String) {
        val updated = load().filterNot { it.sectionId == sectionId && it.playlistId == playlistId }
        if (updated.size == load().size) return
        save(updated)
        HomeFeedCache.invalidate()
        ClientPrefsSync.schedulePush(context.applicationContext)
    }

    fun pinnedSections(playlistId: String): List<String> =
        load().filter { it.playlistId == playlistId }.map { it.sectionId }

    private fun save(pins: List<HomeSectionPin>) = synchronized(lock) {
        inMemoryPins?.let {
            it.clear()
            it.addAll(pins)
            return@synchronized
        }
        prefs().edit().putString(KEY, encode(pins)).apply()
    }

    fun exportJson(): String? {
        val pins = load()
        if (pins.isEmpty()) return null
        return encode(pins)
    }

    fun importJson(raw: String) {
        if (raw.isBlank()) return
        runCatching { save(decode(raw)) }
    }

    private fun encode(pins: List<HomeSectionPin>): String {
        val arr = JSONArray()
        for (pin in pins) {
            arr.put(
                JSONObject()
                    .put("sectionId", pin.sectionId)
                    .put("playlistId", pin.playlistId)
                    .put("playlistName", pin.playlistName)
                    .put("pinnedAtMs", pin.pinnedAtMs),
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String): List<HomeSectionPin> {
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    HomeSectionPin(
                        sectionId = o.getString("sectionId"),
                        playlistId = o.getString("playlistId"),
                        playlistName = o.optString("playlistName", ""),
                        pinnedAtMs = o.optLong("pinnedAtMs", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }

    internal fun resetForTesting() {
        synchronized(lock) {
            inMemoryPins?.clear()
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        }
    }
}
