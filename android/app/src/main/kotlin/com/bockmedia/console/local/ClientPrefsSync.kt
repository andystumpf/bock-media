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
import com.bockmedia.console.domain.model.LibrarySort
import com.bockmedia.console.domain.model.LibrarySessionCache
import com.bockmedia.console.domain.model.LibraryViewMode
import com.bockmedia.console.domain.model.SearchBrowseSessionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _profileChangeRevision = MutableStateFlow(0)
    val profileChangeRevision: StateFlow<Int> = _profileChangeRevision.asStateFlow()

    private fun bumpProfileRevision() {
        _profileChangeRevision.value += 1
    }

    fun schedulePush(context: Context) {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(900)
            runCatching { push(context.applicationContext) }
        }
    }

    suspend fun pullAndApply(context: Context, profileSwitch: Boolean = false) {
        if (pulling) return
        pulling = true
        try {
            val app = BockMediaApp.get(context)
            val repository = app.repository
            val prefs = app.preferences
            val clientId = ClientIdStore.clientId(context)
            rebindFromPhone(context, repository, clientId)
            val memberId = ActiveProfileStore.activeMemberId(context)
            val remote = repository.clientPrefs(clientId, memberId)
            applyMerged(context, prefs, remote, profileSwitch = profileSwitch)
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
            if (profileSwitch || shouldRefreshHomeForProfile(context)) {
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
        runCatching {
            repository.connectInstall(phoneId, label, clientId)
        }
    }

    /** No-op — profile is chosen explicitly in [ProfilePickerGate] or Family. */
    suspend fun ensureProfileLinked(context: Context): Boolean = false

    private fun shouldRefreshHomeForProfile(context: Context): Boolean {
        if (ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return false
        val cached = HomeFeedCache.peek() ?: return false
        return cached.sections.none { it.kind == HomeSectionKind.RatedSongs }
    }

    suspend fun push(context: Context, memberIdOverride: String? = null) {
        val app = BockMediaApp.get(context)
        val repository = app.repository
        val prefs = app.preferences
        val clientId = ClientIdStore.clientId(context)
        val memberId = memberIdOverride?.takeIf { it.isNotBlank() }
            ?: ActiveProfileStore.activeMemberId(context)
        repository.putClientPrefs(
            clientId = clientId,
            memberId = memberId,
            memberPrefs = collectMemberPrefs(context, prefs, memberId),
            clientPrefs = collectClientPrefs(context),
        )
    }

    suspend fun onActiveMemberChanged(
        context: Context,
        memberId: String?,
        previousMemberId: String?,
    ) {
        val app = BockMediaApp.get(context)
        val clientId = ClientIdStore.clientId(context)
        // Save outgoing profile to the server before switching local state.
        if (!previousMemberId.isNullOrBlank()) {
            runCatching { push(context, previousMemberId) }
        }
        if (memberId.isNullOrBlank()) {
            ActiveProfileStore.chooseUnattributed(context)
        } else {
            ActiveProfileStore.setActiveMember(context, memberId)
        }
        OfflineDownloadManager.onActiveProfileChanged(context, previousMemberId)
        app.repository.clearRatingsCache()
        app.repository.invalidatePlaylistsCache()
        HomeFeedCache.invalidate()
        HomeLoadCoordinator.resetReloadWindow()
        LibrarySessionCache.invalidate()
        SearchBrowseSessionCache.invalidate()
        runCatching { app.repository.bindClient(clientId, memberId, InstallIdentity.phoneId(context)) }
        runCatching { pullAndApply(context, profileSwitch = true) }
        bumpProfileRevision()
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
        put("nowPlayingVideo", prefs.isNowPlayingVideoSync())
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
            val offline = OfflineDownloadSync.recordsForMember(context, memberId)
            if (offline.isNotEmpty()) {
                put("offlineDownloads", bockJson.parseToJsonElement(OfflineDownloadSync.encode(offline)))
            }
            val library = LibraryPrefsStore(context).loadSync()
            put("libraryTab", LibraryPrefsStore.filterTabValue(library.filter))
            put("libraryViewMode", if (library.viewMode == LibraryViewMode.Grid) "grid" else "list")
            put("librarySortBy", if (library.sort == LibrarySort.Recents) "recents" else "name")
            put("librarySortOrder", if (library.sort == com.bockmedia.console.domain.model.LibrarySort.Recents) "desc" else "asc")
        }
    }

    private suspend fun collectClientPrefs(context: Context): JsonObject = buildJsonObject {
        // Device playback prefs live on the household member (survive reinstall).
    }

    private suspend fun applyMerged(
        context: Context,
        prefs: AppPreferences,
        remote: ClientPrefsResponse,
        profileSwitch: Boolean = false,
    ) {
        val merged = if (profileSwitch) remote.memberPrefs else remote.merged
        if (merged.isEmpty() && !profileSwitch) return
        merged["searchAllLibraries"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setSearchAllLibraries(it)
        } ?: run {
            if (profileSwitch) prefs.setSearchAllLibraries(true)
        }
        when {
            merged["searchSourcePath"]?.jsonPrimitive?.content?.isNotBlank() == true -> {
                prefs.setSearchSourcePath(merged["searchSourcePath"]!!.jsonPrimitive.content)
            }
            profileSwitch || merged.containsKey("searchSourcePath") -> {
                prefs.setSearchSourcePath(null)
            }
        }
        merged["downloadWifiOnly"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setDownloadWifiOnly(it)
        } ?: run {
            if (profileSwitch) prefs.setDownloadWifiOnly(false)
        }
        merged["crossfadeSeconds"]?.jsonPrimitive?.intOrNull?.let {
            prefs.setCrossfadeSeconds(it)
        } ?: run {
            if (profileSwitch) prefs.setCrossfadeSeconds(0)
        }
        merged["continueAfterQueue"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
            prefs.setContinueAfterQueue(it)
        } ?: run {
            if (profileSwitch) prefs.setContinueAfterQueue("off")
        }
        merged["nowPlayingVideo"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setNowPlayingVideo(it)
        } ?: run {
            if (profileSwitch) prefs.setNowPlayingVideo(false)
        }
        merged["rememberMe"]?.jsonPrimitive?.booleanOrNull?.let {
            prefs.setRememberMe(it)
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
        val offlineRecords = decodeOfflineDownloads(merged["offlineDownloads"])
        if (offlineRecords != null) {
            OfflineDownloadSync.applyRemote(context, offlineRecords)
        } else if (profileSwitch) {
            OfflineDownloadSync.applyRemote(context, emptyList())
        }
        if (merged.containsKey("libraryTab") ||
            merged.containsKey("libraryViewMode") ||
            merged.containsKey("librarySortBy")
        ) {
            LibraryPrefsStore(context).applyRemote(
                tab = merged["libraryTab"]?.jsonPrimitive?.content,
                viewMode = merged["libraryViewMode"]?.jsonPrimitive?.content,
                sortBy = merged["librarySortBy"]?.jsonPrimitive?.content,
                sortOrder = merged["librarySortOrder"]?.jsonPrimitive?.content,
            )
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
