package com.bockmedia.console.local

import android.content.Context
import android.util.Log
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
import com.bockmedia.console.media.LocalPlaybackDuration
import com.bockmedia.console.media.LocalPlaybackQueueResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object OfflineDownloadManager {
    private const val TAG = "BockOffline"
    private const val MAX_TRACKS = 150
    private const val DEFAULT_BUFFER_BYTES = 64 * 1024
    // Transcode streams can take a long time to produce the first byte under load.
    private const val DOWNLOAD_READ_TIMEOUT_SEC = 300L
    private const val DOWNLOAD_CALL_TIMEOUT_SEC = 600L
    private const val TRACK_ATTEMPTS = 3

    // On cellular we ask the server to transcode to a small MP3 (~128 kbps) instead
    // of pulling the full-size original. Audio is bandwidth-bound on cell, so fewer
    // bytes is the single biggest speedup. Wi-Fi/LAN keeps original quality.
    private const val CELLULAR_BITRATE_KBPS = 128
    // FLAC/WMA/etc. require server transcode — request an explicit bitrate on Wi-Fi too
    // (otherwise the server returns 415 when FlacSupport is off).
    private const val WIFI_TRANSCODE_BITRATE_KBPS = 192

    // Parallel download fan-out. Wi-Fi/LAN can saturate many sockets. On cellular the
    // bottleneck is total bandwidth (often the server's home uplink), so extra parallel
    // streams don't add throughput — they just split the pipe until each track is slow
    // enough to hit the call timeout and fail. Keep cellular low so every track finishes.
    // Cap Wi-Fi fan-out so server-side ffmpeg transcodes aren't starved.
    private fun downloadConcurrency(): Int = if (NetworkReachability.onWifi) 3 else 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val profileSuspendedIds = ConcurrentHashMap.newKeySet<String>()
    private val _statuses = MutableStateFlow<Map<String, OfflineCollectionStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, OfflineCollectionStatus>> = _statuses.asStateFlow()

    fun visibleStatuses(context: Context): Map<String, OfflineCollectionStatus> {
        val ids = OfflineDownloadSync.visibleCollectionIds(context)
        return _statuses.value.filterKeys { it in ids }
    }

    fun isVisible(context: Context, collectionId: String): Boolean =
        collectionId in OfflineDownloadSync.visibleCollectionIds(context)

    fun onActiveProfileChanged(context: Context, previousMemberId: String?) {
        val appContext = context.applicationContext
        if (!previousMemberId.isNullOrBlank()) {
            suspendDownloadsForMember(appContext, previousMemberId)
        }
        refresh(appContext)
    }

    /** Stop in-flight jobs for a profile without marking collections failed. */
    fun suspendDownloadsForMember(context: Context, memberId: String) {
        OfflineDownloadSync.collectionIdsForMember(context, memberId).forEach { id ->
            if (!activeJobs.containsKey(id)) return@forEach
            profileSuspendedIds.add(id)
            cancelFlags.computeIfAbsent(id) { AtomicBoolean(false) }.set(true)
            activeJobs[id]?.cancel()
            activeJobs.remove(id)
            cancelFlags.remove(id)
        }
    }

    fun refresh(context: Context) {
        val store = OfflineDownloadStore(context)
        val persisted = store.listManifests().associate { manifest ->
            val complete = store.isCollectionComplete(manifest)
            val state = if (complete) DownloadState.Complete else DownloadState.Failed
            val progress = if (complete) 1f else store.completionProgress(manifest)
            manifest.id to OfflineCollectionStatus(manifest, state, progress)
        }
        _statuses.value = persisted + _statuses.value.filterValues {
            it.state == DownloadState.Downloading || it.state == DownloadState.Idle
        }
    }

    fun isDownloaded(context: Context, target: PlayTarget): Boolean {
        val id = target.downloadId()
        if (id !in OfflineDownloadSync.visibleCollectionIds(context)) return false
        return _statuses.value[id]?.state == DownloadState.Complete
    }

    fun statusFor(context: Context, target: PlayTarget): OfflineCollectionStatus? {
        val id = target.downloadId()
        if (id !in OfflineDownloadSync.visibleCollectionIds(context)) return null
        return _statuses.value[id]
    }

    fun downloadPlaylist(context: Context, playlistId: String, playlistName: String) {
        download(context, PlayTarget.Playlist(playlistId, playlistName))
    }

    fun download(context: Context, target: PlayTarget) {
        val appContext = context.applicationContext
        OfflineDownloadSync.register(appContext, target)
        ClientPrefsSync.schedulePush(appContext)
        val id = target.downloadId()
        val state = _statuses.value[id]?.state
        if (state == DownloadState.Downloading) return
        if (state == DownloadState.Idle && activeJobs.containsKey(id)) return
        val store = OfflineDownloadStore(appContext)
        val existing = store.readManifest(id)
        if (isDownloadSlotBusy(id)) {
            updateStatus(
                id,
                OfflineCollectionStatus(
                    existing ?: manifestFor(
                        id,
                        target.label,
                        target.downloadKindLabel().lowercase(),
                        (target as? PlayTarget.Playlist)?.id,
                        emptyList(),
                    ),
                    DownloadState.Idle,
                    existing?.let { store.completionProgress(it) } ?: 0f,
                ),
            )
        }
        cancelFlags[id] = AtomicBoolean(false)
        activeJobs[id] = scope.launch {
            val blocked = OfflineDownloadNetwork.blockedReason(appContext)
            if (blocked != null) {
                val existing = OfflineDownloadStore(appContext).readManifest(id)
                updateStatus(
                    id,
                    OfflineCollectionStatus(
                        existing ?: manifestFor(id, target.label, target.downloadKindLabel().lowercase(), (target as? PlayTarget.Playlist)?.id, emptyList()),
                        DownloadState.Failed,
                        existing?.let { OfflineDownloadStore(appContext).completionProgress(it) } ?: 0f,
                        blocked,
                    ),
                )
                return@launch
            }
            mutex.withLock {
                downloadOrSyncLocked(appContext, target, resyncOnly = false)
            }
        }.also { job ->
            job.invokeOnCompletion { activeJobs.remove(id) }
        }
    }

    fun cancelCollection(id: String) {
        cancelFlags.computeIfAbsent(id) { AtomicBoolean(false) }.set(true)
        activeJobs[id]?.cancel()
        val existing = _statuses.value[id]
        if (existing?.state == DownloadState.Downloading || existing?.state == DownloadState.Idle) {
            updateStatus(
                id,
                OfflineCollectionStatus(
                    existing.manifest,
                    DownloadState.Failed,
                    existing.progress,
                    "Cancelled",
                ),
            )
        }
        cancelFlags.remove(id)
        activeJobs.remove(id)
    }

    fun resync(context: Context, target: PlayTarget) {
        val appContext = context.applicationContext
        scope.launch {
            if (!OfflineDownloadNetwork.canDownloadNow(appContext)) return@launch
            mutex.withLock {
                downloadOrSyncLocked(appContext, target, resyncOnly = true)
            }
        }
    }

    fun onNetworkAvailable(context: Context) {
        scope.launch {
            if (!OfflineDownloadNetwork.canDownloadNow(context.applicationContext)) return@launch
            syncAll(context.applicationContext)
        }
    }

    suspend fun syncAll(context: Context) {
        mutex.withLock {
            syncAllLocked(context)
        }
    }

    private suspend fun syncAllLocked(context: Context) {
        if (!OfflineDownloadNetwork.canDownloadNow(context)) return
        refresh(context)
        val visibleIds = OfflineDownloadSync.visibleCollectionIds(context)
        // Failed collections are not retried here — the user must tap Retry.
        val store = OfflineDownloadStore(context)
        store.listManifests()
            .filter { manifest -> manifest.id in visibleIds && store.isCollectionComplete(manifest) }
            .forEach { manifest ->
                downloadOrSyncLocked(context, manifest.toPlayTarget(), resyncOnly = true)
            }
    }

    fun deleteCollection(context: Context, collectionId: String) {
        OfflineDownloadStore(context).deleteCollection(collectionId)
        _statuses.value = _statuses.value - collectionId
        OfflineDownloadSync.remove(context, collectionId)
        ClientPrefsSync.schedulePush(context.applicationContext)
    }

    fun retry(context: Context, collectionId: String) {
        val status = _statuses.value[collectionId] ?: return
        download(context, status.manifest.toPlayTarget())
    }

    private suspend fun downloadOrSyncLocked(context: Context, target: PlayTarget, resyncOnly: Boolean) {
        val app = BockMediaApp.get(context)
        val repository = app.repository
        val store = OfflineDownloadStore(context)
        val id = target.downloadId()
        val title = target.label
        val kind = target.downloadKindLabel().lowercase()
        val sourcePlaylistId = (target as? PlayTarget.Playlist)?.id
        val existing = store.readManifest(id)

        updateStatus(
            id,
            OfflineCollectionStatus(
                existing ?: manifestFor(id, title, kind, sourcePlaylistId, emptyList()),
                DownloadState.Downloading,
                existing?.let { store.completionProgress(it) } ?: 0f,
            ),
        )
        OfflineDownloadForegroundService.start(context)

        runCatching {
            val resolver = LocalPlaybackQueueResolver(repository, store)
            // Playlists download in full; artist/album/radio mixes stay capped so a huge
            // library artist doesn't try to sync thousands of tracks at once.
            val trackCap = if (target is PlayTarget.Playlist) null else MAX_TRACKS
            val resolved = resolver.resolve(target, shuffle = false, maxTracks = trackCap)
            if (resolved.isEmpty()) error("No downloadable tracks found")

            val mergedTracks = store.mergeTrackEntries(existing, resolved, id)
            if (mergedTracks.isEmpty()) error("No downloadable tracks found")

            val allOnDisk = mergedTracks.all { entry ->
                store.resolveTrackFile(
                    (existing ?: manifestFor(id, title, kind, sourcePlaylistId, mergedTracks)).copy(tracks = mergedTracks),
                    entry,
                ) != null
            }
            if (resyncOnly && allOnDisk) {
                val manifest = manifestFor(id, title, kind, sourcePlaylistId, mergedTracks)
                    .copy(lastSyncedAtMs = System.currentTimeMillis())
                store.saveManifest(manifest)
                updateStatus(id, OfflineCollectionStatus(manifest, DownloadState.Complete, 1f))
                return
            }

            val base = app.resolveBaseUrl()
            Log.i(TAG, "download base=$base collection=$id tracks=${mergedTracks.size}")
            // Per-call ceiling so a stalled connection (common on cellular/external links)
            // can't wedge the whole download forever.
            val client = app.buildLiveAuthHttpClient(readTimeoutSec = DOWNLOAD_READ_TIMEOUT_SEC)
                .newBuilder()
                .callTimeout(DOWNLOAD_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(DOWNLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build()
            val workingManifest = manifestFor(id, title, kind, sourcePlaylistId, mergedTracks)

            // Download tracks concurrently. A single sequential stream was the main
            // slowdown — especially on cellular, where per-file latency dominates and
            // overlapping requests recover most of the wasted round-trip time.
            // supervisorScope + per-track try/catch keeps one flaky track from cancelling
            // (and wedging) the rest; completed tracks persist so a retry resumes.
            val tracksOnDisk = java.util.Collections.synchronizedList(mutableListOf<OfflineTrackEntry>())
            val failedTracks = java.util.Collections.synchronizedList(mutableListOf<String>())
            val progressMutex = Mutex()
            val gate = Semaphore(downloadConcurrency())
            supervisorScope {
                mergedTracks.map { entry ->
                    async {
                        if (cancelFlags[id]?.get() == true) return@async
                        try {
                            gate.withPermit {
                                if (cancelFlags[id]?.get() == true) return@withPermit
                                val dest = store.trackFile(id, entry.fileName)
                                if (!(dest.exists() && dest.length() > 0)) {
                                    downloadTrack(client, base, entry, dest, id, app.preferences.mobileTokenNow())
                                }
                                progressMutex.withLock {
                                    tracksOnDisk.add(entry)
                                    publishProgress(store, workingManifest, tracksOnDisk.toList(), mergedTracks)
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            val label = entry.title.ifBlank { entry.path }
                            failedTracks.add("$label (${e.message ?: "failed"})")
                        }
                    }
                }.awaitAll()
            }
            if (cancelFlags[id]?.get() == true) error("Cancelled")
            if (failedTracks.isNotEmpty()) {
                val preview = failedTracks.take(3).joinToString("; ")
                val more = if (failedTracks.size > 3) " (+${failedTracks.size - 3} more)" else ""
                error("${failedTracks.size} track(s) failed: $preview$more — tap retry to resume")
            }

            val manifest = workingManifest.copy(
                tracks = mergedTracks,
                lastSyncedAtMs = System.currentTimeMillis(),
            )
            store.saveManifest(manifest)
            store.pruneOrphanFiles(id, mergedTracks)
            updateStatus(id, OfflineCollectionStatus(manifest, DownloadState.Complete, 1f))
            if (!resyncOnly) {
                DeviceAnalyticsReporter.reportDownload(
                    context,
                    collectionTitle = title,
                    collectionKind = kind,
                    trackCount = mergedTracks.size,
                )
            }
            ClientPrefsSync.schedulePush(context)
        }.onFailure { e ->
            if (profileSuspendedIds.remove(id)) {
                return@onFailure
            }
            val partial = store.readManifest(id)
            val progress = partial?.let { store.completionProgress(it) } ?: 0f
            updateStatus(
                id,
                OfflineCollectionStatus(
                    partial ?: manifestFor(id, title, kind, sourcePlaylistId, emptyList()),
                    DownloadState.Failed,
                    progress,
                    e.message,
                ),
            )
        }
    }

    /**
     * Download one track to a .part file then atomically rename. Retries transient
     * failures (cellular drops, timeouts) with backoff, discarding the partial each
     * time so the .part never lingers on a failed attempt.
     */
    private suspend fun downloadTrack(
        client: OkHttpClient,
        base: String,
        entry: OfflineTrackEntry,
        dest: File,
        id: String,
        mediaSignSecret: String?,
    ) {
        val needsTranscode = LocalPlaybackDuration.needsStreamTranscode(entry.path)
        val bitrate = when {
            needsTranscode && NetworkReachability.onWifi -> WIFI_TRANSCODE_BITRATE_KBPS
            needsTranscode && !NetworkReachability.onWifi -> CELLULAR_BITRATE_KBPS
            else -> null
        }
        if (!AppPreferences.isValidLibraryPath(entry.path)) {
            error("Invalid library path")
        }
        val url = AppPreferences.streamUrl(
            base,
            entry.path,
            title = entry.title,
            artist = entry.artist,
            bitrateKbps = bitrate,
            mediaSignSecret = mediaSignSecret,
        ) ?: error("Bad stream URL")
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, "${dest.name}.part")
        var lastError: Exception? = null
        repeat(TRACK_ATTEMPTS) { attempt ->
            if (cancelFlags[id]?.get() == true) error("Cancelled")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} path=${entry.path} url=$url")
                        error("HTTP ${response.code}")
                    }
                    response.body?.byteStream()?.use { input ->
                        part.outputStream().use { output ->
                            input.copyTo(output, DEFAULT_BUFFER_BYTES)
                        }
                    } ?: error("Empty response")
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return
            } catch (ce: CancellationException) {
                part.delete()
                throw ce
            } catch (e: Exception) {
                lastError = e
                part.delete()
                if (attempt < TRACK_ATTEMPTS - 1) delay(750L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private fun publishProgress(
        store: OfflineDownloadStore,
        manifest: OfflineCollectionManifest,
        done: List<OfflineTrackEntry>,
        all: List<OfflineTrackEntry>,
    ) {
        val progressManifest = manifest.copy(
            tracks = all,
            coverArtPath = done.firstOrNull()?.path ?: manifest.coverArtPath,
        )
        store.saveManifest(progressManifest)
        updateStatus(
            manifest.id,
            OfflineCollectionStatus(
                progressManifest,
                DownloadState.Downloading,
                done.size.toFloat() / all.size.coerceAtLeast(1),
            ),
        )
    }

    private fun manifestFor(
        id: String,
        title: String,
        kind: String,
        sourcePlaylistId: String?,
        tracks: List<OfflineTrackEntry>,
    ) = OfflineCollectionManifest(
        id = id,
        title = title,
        kind = kind,
        sourcePlaylistId = sourcePlaylistId,
        coverArtPath = tracks.firstOrNull()?.path,
        tracks = tracks,
    )

    private fun updateStatus(id: String, status: OfflineCollectionStatus) {
        _statuses.value = _statuses.value + (id to status)
    }

    /** True when another collection is downloading or waiting on the download mutex. */
    private fun isDownloadSlotBusy(excludingId: String): Boolean {
        if (activeJobs.keys.any { it != excludingId }) return true
        return _statuses.value.values.any {
            it.manifest.id != excludingId && it.state == DownloadState.Downloading
        }
    }
}
