package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.api.dto.ClientPrefsResponse
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeTileEngagement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Syncs phone settings and preferences to the server (per profile + install). */
object ClientPrefsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    @Volatile private var pulling = false

    fun schedulePush(context: Context) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(900)
            runCatching { push(context.applicationContext) }
        }
    }

    suspend fun pullAndApply(context: Context) {
        if (pulling) return
        pulling = true
        try {
            val app = BockMediaApp.get(context)
            val repository = app.repository
            val prefs = app.preferences
            val clientId = ClientIdStore.clientId(context)
            restoreMemberFromHousehold(context, repository, clientId)
            val memberId = ActiveProfileStore.activeMemberId(context)
            val remote = repository.clientPrefs(clientId, memberId)
            applyMerged(context, prefs, remote)
            if (!memberId.isNullOrBlank()) {
                runCatching { repository.bindClient(clientId, memberId) }
            }
        } finally {
            pulling = false
        }
    }

    suspend fun push(context: Context) {
        val app = BockMediaApp.get(context)
        val repository = app.repository
        val prefs = app.preferences
        val clientId = ClientIdStore.clientId(context)
        val memberId = ActiveProfileStore.activeMemberId(context)
        repository.putClientPrefs(
            clientId = clientId,
            memberId = memberId,
            memberPrefs = collectMemberPrefs(context, prefs, memberId),
            clientPrefs = collectClientPrefs(context),
        )
    }

    suspend fun onActiveMemberChanged(context: Context, memberId: String?) {
        val app = BockMediaApp.get(context)
        val clientId = ClientIdStore.clientId(context)
        app.repository.clearRatingsCache()
        runCatching { app.repository.bindClient(clientId, memberId) }
        runCatching { push(context) }
        runCatching { pullAndApply(context) }
    }

    private suspend fun restoreMemberFromHousehold(
        context: Context,
        repository: BockMediaRepository,
        clientId: String,
    ) {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return
        val deviceId = repository.clientDeviceId()
        val household = runCatching { repository.household() }.getOrNull() ?: return
        val bound = household.clientBindings.firstOrNull {
            it.clientDeviceId == deviceId || it.clientDeviceId == clientId
        }?.memberId?.takeIf { it.isNotBlank() } ?: return
        ActiveProfileStore.setActiveMember(context, bound)
    }

    private suspend fun collectMemberPrefs(
        context: Context,
        prefs: AppPreferences,
        memberId: String?,
    ): JsonObject = buildJsonObject {
        put("searchAllLibraries", prefs.isSearchAllLibrariesSync())
        prefs.getSearchSourcePathSync()?.let { put("searchSourcePath", it) }
        put("downloadWifiOnly", prefs.isDownloadWifiOnlySync())
        put("crossfadeSeconds", prefs.getCrossfadeSecondsSync())
        put("continueAfterQueue", prefs.getContinueAfterQueueSync())
        put("rememberMe", prefs.isRememberMeSync())
        memberId?.let { put("activeMemberId", it) }
        put("searchSelections", encodeSearchSelections(SearchHistoryStore(context).selectionsSync()))
        HomeTileEngagement.exportJson()?.let { put("homeTileEngagement", JsonPrimitive(it)) }
    }

    private suspend fun collectClientPrefs(context: Context): JsonObject = buildJsonObject {
        LastDeviceStore(context).lastDeviceSync()?.let { put("lastDevice", it) }
        val pinned = PinnedDevicesStore(context).pinnedValuesSync()
        if (pinned.isNotEmpty()) {
            put("pinnedDevices", buildJsonArray { pinned.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private suspend fun applyMerged(
        context: Context,
        prefs: AppPreferences,
        remote: ClientPrefsResponse,
    ) {
        val merged = remote.merged
        if (merged.isEmpty()) return
        merged["searchAllLibraries"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setSearchAllLibraries(it)
        }
        merged["searchSourcePath"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            prefs.setSearchSourcePath(it)
        } ?: merged.containsKey("searchSourcePath").takeIf { it == true }?.let {
            prefs.setSearchSourcePath(null)
        }
        merged["downloadWifiOnly"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setDownloadWifiOnly(it)
        }
        merged["crossfadeSeconds"]?.jsonPrimitive?.intOrNull?.let {
            prefs.setCrossfadeSeconds(it)
        }
        merged["continueAfterQueue"]?.jsonPrimitive?.content?.let {
            prefs.setContinueAfterQueue(it)
        }
        merged["rememberMe"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setRememberMe(it)
        }
        merged["activeMemberId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            ActiveProfileStore.setActiveMember(context, it)
        }
        decodeSearchSelections(merged["searchSelections"])?.let { items ->
            SearchHistoryStore(context).replaceSelections(items)
        }
        merged["homeTileEngagement"]?.jsonPrimitive?.content?.let {
            HomeTileEngagement.importJson(it)
        }
        merged["lastDevice"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            LastDeviceStore(context).setLastDevice(it)
        }
        decodeStringList(merged["pinnedDevices"])?.let { pinned ->
            PinnedDevicesStore(context).setPinned(pinned)
        }
    }

    private fun encodeSearchSelections(items: List<SearchRecentSelection>): JsonElement {
        val serializer = ListSerializer(SearchRecentSelection.serializer())
        return bockJson.parseToJsonElement(bockJson.encodeToString(serializer, items))
    }

    private fun decodeSearchSelections(element: JsonElement?): List<SearchRecentSelection>? {
        if (element == null || element !is JsonArray) return null
        return runCatching {
            bockJson.decodeFromString(
                ListSerializer(SearchRecentSelection.serializer()),
                element.toString(),
            )
        }.getOrNull()
    }

    private fun decodeStringList(element: JsonElement?): List<String>? {
        if (element == null || element !is JsonArray) return null
        return element.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
    }
}
