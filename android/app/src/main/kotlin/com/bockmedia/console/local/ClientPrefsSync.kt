package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.api.dto.ClientPrefsResponse
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.HomeLoadCoordinator
import com.bockmedia.console.domain.model.HomeSectionKind
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

/** Syncs phone settings and preferences to the server (per household profile). */
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
            rebindFromPhone(context, repository, clientId)
            val restored = restoreActiveMember(context, repository, clientId)
            val memberId = ActiveProfileStore.activeMemberId(context)
            val remote = repository.clientPrefs(clientId, memberId)
            applyMerged(context, prefs, remote)
            if (!memberId.isNullOrBlank()) {
                runCatching {
                    repository.bindClient(
                        clientId,
                        memberId,
                        InstallIdentity.phoneId(context),
                    )
                }
            }
            repository.clearRatingsCache()
            if (restored || shouldRefreshHomeForProfile(context)) {
                HomeFeedCache.invalidate()
                HomeLoadCoordinator.resetReloadWindow()
            }
        } finally {
            pulling = false
        }
    }

    private suspend fun rebindFromPhone(
        context: Context,
        repository: BockMediaRepository,
        clientId: String,
    ) {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return
        val phoneId = InstallIdentity.phoneId(context)
        if (phoneId.isBlank()) return
        val model = android.os.Build.MODEL?.trim().orEmpty()
        val label = if (model.isNotBlank()) "Android · $model" else "This phone"
        val memberId = runCatching {
            repository.connectInstall(phoneId, label, clientId)
        }.getOrNull()
        if (!memberId.isNullOrBlank()) {
            ActiveProfileStore.setActiveMember(context, memberId)
        }
    }

    /** Link this install to a household profile when missing (e.g. after reinstall). */
    suspend fun ensureProfileLinked(context: Context): Boolean {
        val app = BockMediaApp.get(context)
        return restoreActiveMember(context, app.repository, ClientIdStore.clientId(context))
    }

    private fun shouldRefreshHomeForProfile(context: Context): Boolean {
        if (ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return false
        val cached = HomeFeedCache.peek() ?: return false
        return cached.sections.none { it.kind == HomeSectionKind.RatedSongs }
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
        HomeFeedCache.invalidate()
        HomeLoadCoordinator.resetReloadWindow()
        runCatching { app.repository.bindClient(clientId, memberId, InstallIdentity.phoneId(context)) }
        runCatching { push(context) }
        runCatching { pullAndApply(context) }
    }

    /** Re-bind this install to a household profile after reinstall (new client id). Returns true if set. */
    private suspend fun restoreActiveMember(
        context: Context,
        repository: BockMediaRepository,
        clientId: String,
    ): Boolean {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return false
        val household = runCatching { repository.household() }.getOrNull() ?: return false
        val deviceId = repository.clientDeviceId()
        val fromBinding = household.clientBindings.firstOrNull {
            it.clientDeviceId == deviceId || it.clientDeviceId == clientId
        }?.memberId?.takeIf { it.isNotBlank() }
        if (fromBinding != null) {
            ActiveProfileStore.setActiveMember(context, fromBinding)
            return true
        }
        val members = household.members
        if (members.isEmpty()) return false
        if (members.size == 1) {
            val only = members[0].id.takeIf { it.isNotBlank() } ?: return false
            ActiveProfileStore.setActiveMember(context, only)
            return true
        }
        // Multi-member: wait for ProfilePickerGate — do not guess another profile.
        return false
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
        if (!memberId.isNullOrBlank()) {
            LastDeviceStore(context).lastDeviceSync()?.let { put("lastDevice", it) }
            val pinned = PinnedDevicesStore(context).pinnedValuesSync()
            if (pinned.isNotEmpty()) {
                put("pinnedDevices", buildJsonArray { pinned.forEach { add(JsonPrimitive(it)) } })
            }
            val offline = OfflineDownloadSync.collectForMember(context)
            if (offline.isNotEmpty()) {
                put("offlineDownloads", bockJson.parseToJsonElement(OfflineDownloadSync.encode(offline)))
            }
        }
    }

    private suspend fun collectClientPrefs(context: Context): JsonObject = buildJsonObject {
        // Device playback prefs live on the household member (survive reinstall).
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
            if (ActiveProfileStore.activeMemberId(context).isNullOrBlank()) {
                ActiveProfileStore.setActiveMember(context, it)
            }
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
        decodeOfflineDownloads(merged["offlineDownloads"])?.let { records ->
            OfflineDownloadSync.applyRemote(context, records)
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

    private fun decodeOfflineDownloads(element: JsonElement?): List<OfflineDownloadRecord>? {
        if (element == null || element !is JsonArray) return null
        return OfflineDownloadSync.decode(element.toString())
    }

    private fun decodeStringList(element: JsonElement?): List<String>? {
        if (element == null || element !is JsonArray) return null
        return element.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
    }
}
