package com.bockmedia.console.widget

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.media.NowPlayingNotificationManager
import kotlinx.coroutines.runBlocking

import java.util.concurrent.atomic.AtomicInteger

object NowPlayingController {

    private val pollGeneration = AtomicInteger(0)

    @Volatile
    private var lastProbeMs = 0L

    fun fetchAndStore(context: Context) {
        val appContext = context.applicationContext
        val base = BockMediaApp.activeBaseUrlBlocking(appContext)
        if (base.isNullOrBlank()) {
            NowPlayingSessionStore.snapshot = null
            NowPlayingSessionStore.focusedDeviceId = null
            return
        }

        val snap = runCatching {
            runBlocking {
                val api = BockMediaApp.apiBlocking(appContext)
                val resp = api.nowPlayingDevices()
                var alexaSerialByName = emptyMap<String, String>()
                var remoteOk = false
                if (resp.controlsAvailable) {
                    runCatching {
                        val st = api.alexaRemoteStatus()
                        if (st.authenticated == true) {
                            val prevSerials = NowPlayingSessionStore.snapshot?.alexaSerialByName.orEmpty()
                            val now = System.currentTimeMillis()
                            val probe = prevSerials.isEmpty() && now - lastProbeMs >= PROBE_MIN_INTERVAL_MS
                            if (probe) lastProbeMs = now
                            alexaSerialByName = api.alexaRemoteDevices(if (probe) 1 else 0).devices.mapNotNull { d ->
                                val name = d.name?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                val serial = d.serial?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                name to serial
                            }.toMap()
                            remoteOk = true
                        }
                    }
                }
                NowPlayingSessionStore.Snapshot(
                    items = resp.items,
                    controlsAvailable = resp.controlsAvailable,
                    baseUrl = base,
                    alexaSerialByName = alexaSerialByName,
                    remoteOk = remoteOk,
                )
            }
        }.getOrNull() ?: return

        NowPlayingSessionStore.snapshot = snap
        if (snap.items.isEmpty()) {
            NowPlayingSessionStore.focusedDeviceId = null
        } else if (snap.items.none { it.deviceId == NowPlayingSessionStore.focusedDeviceId }) {
            NowPlayingSessionStore.focusedDeviceId =
                snap.items.firstOrNull { !it.paused && !it.stopped }?.deviceId ?: snap.items.first().deviceId
        }
    }

    fun sendControl(context: Context, deviceId: String, deviceName: String, action: String): Boolean {
        val before = NowPlayingSessionStore.snapshot?.items?.find { it.deviceId == deviceId }
        val serial = NowPlayingSessionStore.resolveSerial(deviceName) ?: return false
        val ok = runCatching {
            runBlocking {
                val resp = BockMediaApp.get(context.applicationContext).repository.deviceControl(
                    deviceId, deviceName, serial, action,
                )
                if (!resp.ok) error(resp.error ?: "control failed")
            }
        }.isSuccess
        if (ok) {
            refreshAfterControl(context.applicationContext, deviceId, action, before)
        }
        return ok
    }

    private fun refreshAfterControl(
        context: Context,
        deviceId: String,
        action: String,
        before: NowPlayingDeviceItem?,
    ) {
        NowPlayingController.fetchAndStore(context)
        if (action == "next" && before != null) {
            val item = NowPlayingSessionStore.snapshot?.items?.find { it.deviceId == deviceId }
            if (item != null && item.track == before.track && item.filepath == before.filepath) {
                NowPlayingSessionStore.applyOptimisticSkip(deviceId, forward = true)
            }
        }
        NowPlayingWidget.publishSession(context)
        if (action in TRACK_CHANGE_ACTIONS && before != null) {
            pollUntilTrackChange(context, deviceId, before.track, before.filepath)
        }
    }

    private fun pollUntilTrackChange(
        context: Context,
        deviceId: String,
        prevTrack: String?,
        prevPath: String?,
    ) {
        val gen = pollGeneration.incrementAndGet()
        val appContext = context.applicationContext
        Thread {
            repeat(POLL_ATTEMPTS) {
                if (gen != pollGeneration.get()) return@Thread
                Thread.sleep(POLL_INTERVAL_MS)
                fetchAndStore(appContext)
                val item = NowPlayingSessionStore.snapshot?.items?.find { it.deviceId == deviceId }
                if (item != null && (item.track != prevTrack || item.filepath != prevPath)) {
                    NowPlayingWidget.publishSession(appContext)
                    return@Thread
                }
            }
            if (gen == pollGeneration.get()) {
                NowPlayingWidget.publishSession(appContext)
            }
        }.start()
    }

    fun cycleDevice(context: Context, forward: Boolean = true) {
        NowPlayingSessionStore.cycleFocus(forward)
        NowPlayingNotificationManager.sync(context.applicationContext)
        NowPlayingWidget.updateWidgetsOnly(context.applicationContext)
    }

    private val TRACK_CHANGE_ACTIONS = setOf("next", "previous")
    private const val POLL_INTERVAL_MS = 1500L
    private const val POLL_ATTEMPTS = 8
    private const val PROBE_MIN_INTERVAL_MS = 5 * 60_000L
}
