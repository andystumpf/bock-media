package com.bockmedia.console.local

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.data.analytics.DeviceAnalyticsReporter
import com.bockmedia.console.media.LocalPlaybackQueueResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object OfflineDownloadManager {
    private const val MAX_TRACKS = 150
    private const val DEFAULT_BUFFER_BYTES = 64 * 1024

    // Parallel download fan-out. Wi-Fi can saturate more sockets; on cellular we use
    // fewer to hide latency without overwhelming a slower, metered link.
    private fun downloadConcurrency(): Int = if (NetworkReachability.onWifi) 6 else 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val _statuses = MutableStateFlow<Map<String, OfflineCollectionStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, OfflineCollectionStatus>> = _statuses.asStateFlow()

    fun refresh(context: Context) {
        val store = OfflineDownloadStore(context)
        val persisted = store.listManifests().associate { manifest ->
            val complete = store.isCollectionComplete(manifest)
            val state = if (complete) DownloadState.Complete else DownloadState.Failed
            val progress = if (complete) 1f else store.completionProgress(manifest)
            manifest.id to OfflineCollectionStatus(manifest, state, progress)
        }
        _statuses.value = persisted + _statuses.value.filterValues {
            it.state == DownloadState.Downloading
        }
    }

    fun isDownloaded(target: PlayTarget): Boolean =
        _statuses.value[target.downloadId()]?.state == DownloadState.Complete

    fun statusFor(target: PlayTarget): OfflineCollectionStatus? = _statuses.value[target.downloadId()]

    fun downloadPlaylist(context: Context, playlistId: String, playlistName: String) {
        download(context, PlayTarget.Playlist(playlistId, playlistName))
    }

    fun download(context: Context, target: PlayTarget) {
        val appContext = context.applicationContext
        val id = target.downloadId()
        if (_statuses.value[id]?.state == DownloadState.Downloading) return
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
        if (existing?.state == DownloadState.Downloading) {
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
        val store = OfflineDownloadStore(context)
        _statuses.value.filter { it.value.state == DownloadState.Failed }.forEach { (_, status) ->
            downloadOrSyncLocked(context, status.manifest.toPlayTarget(), resyncOnly = false)
        }
        store.listManifests()
            .filter { manifest -> store.isCollectionComplete(manifest) }
            .forEach { manifest ->
                downloadOrSyncLocked(context, manifest.toPlayTarget(), resyncOnly = true)
            }
    }

    fun deleteCollection(context: Context, collectionId: String) {
        OfflineDownloadStore(context).deleteCollection(collectionId)
        _statuses.value = _statuses.value - collectionId
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
            val resolved = resolver.resolve(target, shuffle = false, maxTracks = MAX_TRACKS)
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

            val client = app.buildAuthenticatedHttpClient()
            val base = app.resolveBaseUrl()
            val workingManifest = manifestFor(id, title, kind, sourcePlaylistId, mergedTracks)

            // Download tracks concurrently. A single sequential stream was the main
            // slowdown — especially on cellular, where per-file latency dominates and
            // overlapping requests recover most of the wasted round-trip time.
            val tracksOnDisk = java.util.Collections.synchronizedList(mutableListOf<OfflineTrackEntry>())
            val progressMutex = Mutex()
            val gate = Semaphore(downloadConcurrency())
            coroutineScope {
                mergedTracks.map { entry ->
                    async {
                        gate.withPermit {
                            if (cancelFlags[id]?.get() == true) error("Cancelled")
                            val dest = store.trackFile(id, entry.fileName)
                            if (!(dest.exists() && dest.length() > 0)) {
                                val url = AppPreferences.streamUrl(base, entry.path)
                                    ?: error("Bad stream URL")
                                dest.parentFile?.mkdirs()
                                // Stream to a .part file and atomically rename so an
                                // interrupted download is never mistaken for a complete one.
                                val part = File(dest.parentFile, "${dest.name}.part")
                                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                    if (!response.isSuccessful) error("Download failed (${response.code})")
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
                            }
                            progressMutex.withLock {
                                tracksOnDisk.add(entry)
                                publishProgress(store, workingManifest, tracksOnDisk.toList(), mergedTracks)
                            }
                        }
                    }
                }.awaitAll()
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
        }.onFailure { e ->
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
}
