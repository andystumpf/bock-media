package com.bockmedia.console.data.analytics

import android.content.Context
import android.os.Build
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.domain.model.LocalTrack
import com.bockmedia.console.local.ClientIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DeviceAnalyticsReporter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var lastPlaybackReportMs = 0L
    private const val PLAYBACK_THROTTLE_MS = 4_000L

    fun reportConnect(context: Context) {
        post(context, "connect") {}
    }

    fun reportPlay(context: Context, track: LocalTrack) {
        post(context, "play") {
            put("track", track.title)
            track.artist?.let { put("artist", it) }
            track.album?.let { put("album", it) }
            put("filepath", track.path)
        }
        reportPlayback(context, track, playing = true, offsetMs = 0, durationMs = 0, force = true)
    }

    fun reportPlayback(
        context: Context,
        track: LocalTrack,
        playing: Boolean,
        offsetMs: Long,
        durationMs: Long,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPlaybackReportMs < PLAYBACK_THROTTLE_MS) return
        lastPlaybackReportMs = now
        post(context, "playback") {
            put("track", track.title)
            track.artist?.let { put("artist", it) }
            track.album?.let { put("album", it) }
            put("filepath", track.path)
            put("playing", playing)
            put("paused", !playing)
            put("offset_ms", offsetMs)
            put("duration_ms", durationMs)
        }
    }

    fun clearPlayback(context: Context) {
        lastPlaybackReportMs = 0L
        post(context, "playback") {
            put("stopped", true)
        }
    }

    fun reportDownload(
        context: Context,
        collectionTitle: String,
        collectionKind: String,
        trackCount: Int,
    ) {
        post(context, "download") {
            put("collectionTitle", collectionTitle)
            put("collectionKind", collectionKind)
            put("trackCount", trackCount)
        }
    }

    private fun post(context: Context, event: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        scope.launch {
            runCatching {
                val app = BockMediaApp.get(context.applicationContext)
                if (!app.hasServerUrl()) return@runCatching
                val body = buildJsonObject {
                    put("clientId", ClientIdStore.clientId(context))
                    put("platform", "android")
                    put("deviceName", deviceLabel())
                    put("event", event)
                    extra()
                }
                app.repository.reportClientEvent(body)
            }
        }
    }

    private fun deviceLabel(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isNotBlank()) "Android · $model" else "This phone"
    }
}
