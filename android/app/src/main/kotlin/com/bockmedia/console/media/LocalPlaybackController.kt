package com.bockmedia.console.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.httpErrorMessage
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.domain.model.toLocalPlayContext
import com.bockmedia.console.local.OfflineDownloadStore
import com.bockmedia.console.local.OfflineCollectionManifest
import com.bockmedia.console.local.downloadId
import com.bockmedia.console.data.network.NetworkReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

data class LocalPlaybackState(
    val active: Boolean = false,
    val tracks: List<LocalTrack> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val sourceLabel: String? = null,
    val playlist: String? = null,
    val playlistId: String? = null,
    val activeTarget: PlayTarget? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: LocalTrack? get() = tracks.getOrNull(index)
}

object LocalPlaybackController {
    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun update(block: (LocalPlaybackState) -> LocalPlaybackState) {
        _state.value = block(_state.value)
    }

    suspend fun playTarget(context: Context, target: PlayTarget, shuffle: Boolean) {
        val store = OfflineDownloadStore(context)
        store.readManifest(target.downloadId())?.let { manifest ->
            if (manifest.tracks.isNotEmpty()) {
                playOfflineManifest(context, manifest, shuffle)
                return
            }
        }
        val app = BockMediaApp.get(context)
        val resolver = LocalPlaybackQueueResolver(app.repository, store)
        update { it.copy(loading = true, error = null) }
        runCatching {
            val tracks = resolver.resolve(target, shuffle)
            if (tracks.isEmpty()) error("No playable tracks found")
            val ctx = target.toLocalPlayContext()
            playTracks(
                context,
                tracks,
                shuffle = shuffle,
                sourceLabel = ctx.sourceLabel,
                playlist = ctx.playlist,
                playlistId = ctx.playlistId,
                activeTarget = target,
            )
        }.onFailure { e ->
            update { it.copy(loading = false, error = httpErrorMessage(e, "Playback failed")) }
        }
    }

    suspend fun playOfflineManifest(
        context: Context,
        manifest: OfflineCollectionManifest,
        shuffle: Boolean,
        startIndex: Int = 0,
    ) {
        val store = OfflineDownloadStore(context)
        update { it.copy(loading = true, error = null) }
        runCatching {
            val tracks = store.localTracksForManifest(manifest)
            if (tracks.isEmpty()) error("No offline audio files found on this device")
            val playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId
            playTracks(
                context,
                tracks,
                startIndex = startIndex,
                shuffle = shuffle,
                sourceLabel = "Playlist · ${manifest.title}",
                playlist = manifest.title,
                playlistId = playlistId,
                activeTarget = playlistId?.let { PlayTarget.Playlist(it, manifest.title) },
            )
        }.onFailure { e ->
            update { it.copy(loading = false, error = e.message ?: "Offline playback failed") }
        }
    }

    suspend fun playTracks(
        context: Context,
        tracks: List<LocalTrack>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        sourceLabel: String? = _state.value.sourceLabel,
        playlist: String? = _state.value.playlist,
        playlistId: String? = _state.value.playlistId,
        activeTarget: PlayTarget? = _state.value.activeTarget,
    ) {
        if (tracks.isEmpty()) return
        // Keep canonical order; ExoPlayer's shuffle mode handles randomized traversal so the
        // shuffle toggle can be turned off again without losing the original ordering.
        val ordered = tracks
        val resolvedStart = if (shuffle && startIndex == 0) tracks.indices.random() else startIndex
        val app = BockMediaApp.get(context)
        NetworkReachability.update(context)
        // Prefer cached / configured endpoint so playback starts without a health-probe round trip
        // (critical on cellular + Bluetooth in the car).
        val base = app.repository.peekBaseUrl()
            ?: app.configuredEndpointUrl()?.also { app.repository.primeBaseUrl(it) }
            ?: app.resolveBaseUrl()
        // Stream the original file even on cellular: forcing a ?br=128 transcode makes
        // the server re-encode every track in realtime, which this CPU can't keep up with
        // (≈7s to first byte vs ≈5ms for the original) and stalls playback. Data-saving
        // transcode stays opt-in for explicit offline downloads only.
        val signSecret = app.preferences.mobileTokenNow()
        val urls = ordered.map { track ->
            track.localFile?.let { Uri.fromFile(it).toString() }
                ?: AppPreferences.streamUrl(
                    base,
                    track.path,
                    track.title,
                    track.artist,
                    lowBandwidth = LocalPlaybackDuration.needsStreamTranscode(track.path),
                    mediaSignSecret = signSecret,
                )
                ?: error("Missing stream URL for ${track.title}")
        }
        update {
            it.copy(
                active = true,
                tracks = ordered,
                index = resolvedStart.coerceIn(0, ordered.lastIndex),
                shuffle = shuffle,
                sourceLabel = sourceLabel,
                playlist = playlist,
                playlistId = playlistId,
                activeTarget = activeTarget,
                loading = false,
                error = null,
                isPlaying = true,
            )
        }
        val crossfadeMs = BockMediaApp.get(context).preferences.getCrossfadeSecondsSync() * 1000L
        val intent = Intent(context, LocalPlaybackService::class.java).apply {
            action = LocalPlaybackService.ACTION_PLAY
            putStringArrayListExtra(LocalPlaybackService.EXTRA_URLS, ArrayList(urls))
            putStringArrayListExtra(LocalPlaybackService.EXTRA_TITLES, ArrayList(ordered.map { it.title }))
            putStringArrayListExtra(LocalPlaybackService.EXTRA_ARTISTS, ArrayList(ordered.map { it.displayArtist }))
            putStringArrayListExtra(LocalPlaybackService.EXTRA_ALBUMS, ArrayList(ordered.map { it.album.orEmpty() }))
            putStringArrayListExtra(LocalPlaybackService.EXTRA_PATHS, ArrayList(ordered.map { it.path }))
            putExtra(LocalPlaybackService.EXTRA_DURATIONS_MS, LongArray(ordered.size) { i -> ordered[i].durationMs })
            putExtra(LocalPlaybackService.EXTRA_START_INDEX, resolvedStart.coerceIn(0, ordered.lastIndex))
            putExtra(LocalPlaybackService.EXTRA_SHUFFLE, shuffle)
            putExtra(LocalPlaybackService.EXTRA_CROSSFADE_MS, crossfadeMs)
            activeTarget?.let { target ->
                when (target) {
                    is PlayTarget.Playlist -> {
                        putExtra(LocalPlaybackService.EXTRA_TARGET_KIND, "playlist")
                        putExtra(LocalPlaybackService.EXTRA_TARGET_PLAYLIST_ID, target.id)
                    }
                    is PlayTarget.Album -> {
                        putExtra(LocalPlaybackService.EXTRA_TARGET_KIND, "album")
                        putExtra(LocalPlaybackService.EXTRA_TARGET_ALBUM, target.name)
                        target.artist?.let { putExtra(LocalPlaybackService.EXTRA_TARGET_ARTIST, it) }
                    }
                    is PlayTarget.Artist -> {
                        putExtra(LocalPlaybackService.EXTRA_TARGET_KIND, "artist")
                        putExtra(LocalPlaybackService.EXTRA_TARGET_ARTIST, target.name)
                    }
                    is PlayTarget.Song -> {
                        putExtra(LocalPlaybackService.EXTRA_TARGET_KIND, "song")
                        putExtra(LocalPlaybackService.EXTRA_TARGET_PATH, target.path)
                    }
                    is PlayTarget.Radio -> {
                        putExtra(LocalPlaybackService.EXTRA_TARGET_KIND, "radio")
                    }
                }
            }
        }
        context.startForegroundService(intent)
    }

    fun togglePlayPause(context: Context) {
        val s = _state.value
        if (s.active && s.tracks.isNotEmpty() && !LocalPlaybackService.hasPlayer()) {
            restartScope.launch {
                playTracks(
                    context,
                    s.tracks,
                    startIndex = s.index,
                    shuffle = s.shuffle,
                    sourceLabel = s.sourceLabel,
                    playlist = s.playlist,
                    playlistId = s.playlistId,
                    activeTarget = s.activeTarget,
                )
                if (!s.isPlaying) {
                    context.startService(Intent(context, LocalPlaybackService::class.java).apply {
                        action = LocalPlaybackService.ACTION_TOGGLE
                    })
                }
            }
            return
        }
        context.startService(Intent(context, LocalPlaybackService::class.java).apply {
            action = LocalPlaybackService.ACTION_TOGGLE
        })
    }

    fun skip(context: Context, forward: Boolean) {
        context.startService(Intent(context, LocalPlaybackService::class.java).apply {
            action = if (forward) LocalPlaybackService.ACTION_NEXT else LocalPlaybackService.ACTION_PREVIOUS
        })
    }

    fun setShuffle(context: Context, on: Boolean) {
        update { it.copy(shuffle = on) }
        context.startService(Intent(context, LocalPlaybackService::class.java).apply {
            action = LocalPlaybackService.ACTION_SET_SHUFFLE
            putExtra(LocalPlaybackService.EXTRA_SHUFFLE, on)
        })
    }

    fun stop(context: Context) {
        context.startService(Intent(context, LocalPlaybackService::class.java).apply {
            action = LocalPlaybackService.ACTION_STOP
        })
        _state.value = LocalPlaybackState()
    }

    /** Seeds local playback for UI-test / README Now Playing screenshots (no server playback). */
    fun installUITestPreview() {
        val tracks = listOf(
            LocalTrack(
                path = "/music/Pearl Jam/Ten/01 - Daughter.flac",
                title = "Daughter - Remastered",
                artist = "Pearl Jam",
                album = "Ten",
                durationMs = 235_000,
            ),
            LocalTrack(
                path = "/music/Pearl Jam/Ten/02 - Alive.flac",
                title = "Alive",
                artist = "Pearl Jam",
                album = "Ten",
                durationMs = 341_000,
            ),
            LocalTrack(
                path = "/music/Pearl Jam/Ten/03 - Even Flow.flac",
                title = "Even Flow",
                artist = "Pearl Jam",
                album = "Ten",
                durationMs = 292_000,
            ),
        )
        _state.value = LocalPlaybackState(
            active = true,
            tracks = tracks,
            index = 0,
            isPlaying = true,
            positionMs = 45_000,
            durationMs = 235_000,
            shuffle = false,
            sourceLabel = "Rock Essentials",
            playlist = "Rock Essentials",
            playlistId = "preview",
            activeTarget = PlayTarget.Playlist(id = "preview", name = "Rock Essentials"),
        )
    }

    fun seekTo(context: Context, positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        update { it.copy(positionMs = pos) }
        context.startService(Intent(context, LocalPlaybackService::class.java).apply {
            action = LocalPlaybackService.ACTION_SEEK
            putExtra(LocalPlaybackService.EXTRA_POSITION_MS, pos)
        })
    }

    suspend fun seekToQueueIndex(context: Context, index: Int) {
        val current = _state.value
        if (index !in current.tracks.indices) return
        playTracks(
            context,
            current.tracks,
            startIndex = index,
            shuffle = current.shuffle,
            sourceLabel = current.sourceLabel,
            playlist = current.playlist,
            playlistId = current.playlistId,
            activeTarget = current.activeTarget,
        )
    }

    /** Play a playlist (or jump within the active one) starting at [startIndex]. */
    suspend fun playPlaylistFromIndex(
        context: Context,
        target: PlayTarget.Playlist,
        tracks: List<LocalTrack>,
        startIndex: Int,
        shuffle: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val idx = startIndex.coerceIn(0, tracks.lastIndex)
        val current = _state.value
        if (current.playlistId == target.id && current.tracks.isNotEmpty()) {
            val path = tracks.getOrNull(idx)?.path
            val globalIdx = if (path != null) current.tracks.indexOfFirst { it.path == path } else -1
            if (globalIdx >= 0) {
                seekToQueueIndex(context, globalIdx)
                return
            }
        }
        playTracks(
            context,
            tracks,
            startIndex = idx,
            shuffle = shuffle,
            sourceLabel = "Playlist · ${target.name}",
            playlist = target.name,
            playlistId = target.id,
            activeTarget = target,
        )
    }

    /** Blocking hook for service binder callbacks. */
    fun onServiceState(
        index: Int,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        shuffle: Boolean = _state.value.shuffle,
    ) {
        update {
            it.copy(
                active = true,
                index = index,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                shuffle = shuffle,
                loading = false,
            )
        }
    }
}
