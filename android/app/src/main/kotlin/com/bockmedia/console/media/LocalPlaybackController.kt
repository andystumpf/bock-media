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
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: LocalTrack? get() = tracks.getOrNull(index)
}

object LocalPlaybackController {
    private val _state = MutableStateFlow(LocalPlaybackState())
    val state: StateFlow<LocalPlaybackState> = _state.asStateFlow()

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
            playTracks(
                context,
                tracks,
                startIndex = startIndex,
                shuffle = shuffle,
                sourceLabel = "Playlist · ${manifest.title}",
                playlist = manifest.title,
                playlistId = manifest.sourcePlaylistId ?: manifest.legacyPlaylistId,
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
    ) {
        if (tracks.isEmpty()) return
        // Keep canonical order; ExoPlayer's shuffle mode handles randomized traversal so the
        // shuffle toggle can be turned off again without losing the original ordering.
        val ordered = tracks
        val resolvedStart = if (shuffle && startIndex == 0) tracks.indices.random() else startIndex
        val base = BockMediaApp.get(context).resolveBaseUrl()
        val urls = ordered.map { track ->
            track.localFile?.let { Uri.fromFile(it).toString() }
                ?: AppPreferences.streamUrl(base, track.path)
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
            putExtra(LocalPlaybackService.EXTRA_START_INDEX, resolvedStart.coerceIn(0, ordered.lastIndex))
            putExtra(LocalPlaybackService.EXTRA_SHUFFLE, shuffle)
            putExtra(LocalPlaybackService.EXTRA_CROSSFADE_MS, crossfadeMs)
        }
        context.startForegroundService(intent)
    }

    fun togglePlayPause(context: Context) {
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
        playTracks(context, current.tracks, startIndex = index, shuffle = current.shuffle)
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
