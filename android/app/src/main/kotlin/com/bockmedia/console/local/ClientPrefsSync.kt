package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.api.dto.ClientPrefsResponse
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.HomeLoadCoordinator
import com.bockmedia.console.domain.model.RESUME_PULL_DEBOUNCE_MS
import com.bockmedia.console.domain.model.shouldRefreshHomeForProfile
import com.bockmedia.console.domain.model.shouldSkipResumePull
import com.bockmedia.console.domain.model.HomeTileEngagement
import com.bockmedia.console.local.HomeSectionPinsStore
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
    @Volatile private var lastPullCompletedMs: Long = 0L

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

    fun markBootPullCompleted() {
        lastPullCompletedMs = System.currentTimeMillis()
    }

    fun shouldSkipResumePull(): Boolean =
        shouldSkipResumePull(lastPullCompletedMs, System.currentTimeMillis(), RESUME_PULL_DEBOUNCE_MS)

    suspend fun pullAndApply(context: Context, profileSwitch: Boolean = false) {
        if (pulling) return
        pulling = true
        try {
            val app = BockMediaApp.get(context)
            val repository = app.repository
            val prefs = app.preferences
            val clientId = ClientIdStore.clientId(context)
            val profileAdjusted = runCatching {
                syncHouseholdProfile(context, repository, clientId)
            }.getOrElse { return }
            var memberId = ActiveProfileStore.activeMemberId(context)
            val remote = runCatching { repository.clientPrefs(clientId, memberId) }
                .getOrElse { return }
            val merged = if (profileSwitch) remote.memberPrefs else remote.merged
            if (profileAdjusted && memberId.isNullOrBlank() &&
                !ActiveProfileStore.hasProfileChoice(context)
            ) {
                merged["activeMemberId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { fromPrefs ->
                        if (HouseholdStore.memberExists(fromPrefs)) {
                            ActiveProfileStore.setActiveMember(context, fromPrefs)
                            memberId = fromPrefs
                        }
                    }
            }
            applyMerged(context, prefs, remote, profileSwitch = profileSwitch)
            memberId = ActiveProfileStore.activeMemberId(context)
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
            if (profileSwitch || shouldRefreshHomeForProfile(
                    !ActiveProfileStore.activeMemberId(context).isNullOrBlank(),
                    HomeFeedCache.peek(),
                    HomeFeedCache.peekHasRatedSongs(),
                )
            ) {
                HomeFeedCache.invalidate()
                HomeLoadCoordinator.resetReloadWindow()
            }
            lastPullCompletedMs = System.currentTimeMillis()
        } finally {
            pulling = false
        }
    }

    private suspend fun syncHouseholdProfile(
        context: Context,
        repository: BockMediaRepository,
        clientId: String,
    ): Boolean {
        val household = HouseholdStore.refresh(repository)
        val beforeMember = ActiveProfileStore.activeMemberId(context)
        rebindFromPhone(context, repository, clientId, household)
        var changed = reconcileActiveMember(context, household)
        if (restoreActiveMember(context, household, clientId)) changed = true
        if (beforeMember != ActiveProfileStore.activeMemberId(context)) changed = true
        if (changed) bumpProfileRevision()
        return changed
    }

    private fun reconcileActiveMember(context: Context, household: com.bockmedia.console.data.api.dto.HouseholdResponse): Boolean {
        val mid = ActiveProfileStore.activeMemberId(context)?.trim().orEmpty()
        if (mid.isEmpty() || household.members.any { it.id == mid }) return false
        ActiveProfileStore.clearStaleMember(context)
        return true
    }

    private fun restoreActiveMember(
        context: Context,
        household: com.bockmedia.console.data.api.dto.HouseholdResponse,
        clientId: String,
    ): Boolean {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return false
        val deviceId = clientDeviceId(clientId)
        val fromBinding = household.clientBindings.firstOrNull { it.clientDeviceId == deviceId }?.memberId
        if (!fromBinding.isNullOrBlank() && household.members.any { it.id == fromBinding }) {
            ActiveProfileStore.setActiveMember(context, fromBinding)
            return true
        }
        if (!ActiveProfileStore.hasProfileChoice(context) && household.members.size == 1) {
            household.members.firstOrNull()?.id?.takeIf { it.isNotBlank() }?.let {
                ActiveProfileStore.setActiveMember(context, it)
                return true
            }
        }
        return false
    }

    private fun clientDeviceId(clientId: String): String {
        val cid = clientId.trim()
        return if (cid.isEmpty()) "" else "client-$cid"
    }

    private suspend fun rebindFromPhone(
        context: Context,
        repository: BockMediaRepository,
        clientId: String,
        household: com.bockmedia.console.data.api.dto.HouseholdResponse,
    ) {
        if (!ActiveProfileStore.activeMemberId(context).isNullOrBlank()) return
        val phoneId = InstallIdentity.phoneId(context)
        if (phoneId.isBlank()) return
        val model = android.os.Build.MODEL?.trim().orEmpty()
        val label = if (model.isNotBlank()) "Android · $model" else "This phone"
        val memberId = runCatching {
            repository.connectInstall(phoneId, label, clientId)
        }.getOrNull()?.trim().orEmpty()
        if (memberId.isNotBlank() && household.members.any { it.id == memberId }) {
            ActiveProfileStore.setActiveMember(context, memberId)
        }
    }

    /** No-op — profile is chosen explicitly in [ProfilePickerGate] or Family. */
    suspend fun ensureProfileLinked(context: Context): Boolean = false

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
        // Persist locally first so the picker dismisses even if sync is slow.
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
        bumpProfileRevision()
        // Save outgoing profile to the server before binding the new one.
        if (!previousMemberId.isNullOrBlank()) {
            runCatching { push(context, previousMemberId) }
        }
        runCatching { app.repository.bindClient(clientId, memberId, InstallIdentity.phoneId(context)) }
        runCatching { pullAndApply(context, profileSwitch = true) }
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
        HomeSectionPinsStore.exportJson()?.let { put("homeSectionPins", JsonPrimitive(it)) }
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
        merged["homeSectionPins"]?.jsonPrimitive?.content?.let {
            HomeSectionPinsStore.importJson(it)
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
