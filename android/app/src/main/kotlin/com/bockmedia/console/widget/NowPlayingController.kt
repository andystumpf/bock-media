package com.bockmedia.console.widget

import android.content.Context
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.local.ClientIdStore
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.domain.model.PlaybackFocus
import com.bockmedia.console.media.NowPlayingNotificationManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object NowPlayingController {

    fun fetchAndStore(context: Context) {
        val appContext = context.applicationContext
        if (!hasAuthCredentials(appContext)) {
            NowPlayingSessionStore.snapshot = null
            NowPlayingSessionStore.focusedDeviceId = null
            return
        }
        val base = BockMediaApp.activeBaseUrlBlocking(appContext)
        if (base.isNullOrBlank()) {
            NowPlayingSessionStore.snapshot = null
            NowPlayingSessionStore.focusedDeviceId = null
            return
        }

        val fetched = runCatching {
            runBlocking {
                val api = BockMediaApp.apiBlocking(appContext)
                val resp = api.nowPlayingDevices(
                    viewerClientId = ClientIdStore.clientId(appContext),
                )
                var alexaDevices = emptyList<com.bockmedia.console.data.api.dto.AlexaDevice>()
                var alexaSerialByName = emptyMap<String, String>()
                var remoteOk = false
                if (resp.controlsAvailable) {
                    runCatching {
                        alexaDevices = api.alexaRemoteDevices().devices
                        alexaSerialByName = alexaDevices.mapNotNull { d ->
                            val name = d.name?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            val serial = d.serial?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            name to serial
                        }.toMap()
                        remoteOk = true
                    }
                }
                Pair(
                    NowPlayingSessionStore.Snapshot(
                        items = resp.items,
                        controlsAvailable = resp.controlsAvailable,
                        baseUrl = base,
                        alexaSerialByName = alexaSerialByName,
                        remoteOk = remoteOk,
                    ),
                    alexaDevices,
                )
            }
        }.getOrNull() ?: return

        val snap = fetched.first
        val alexaDevices = fetched.second
        NowPlayingSessionStore.snapshot = snap
        if (snap.items.isEmpty()) {
            NowPlayingSessionStore.focusedDeviceId = null
        } else {
            PlaybackFocus.syncPendingFocus(snap.items, alexaDevices)
            NowPlayingSessionStore.focusedDeviceId = PlaybackFocus.focusedDeviceId
                ?: snap.items.firstOrNull { !it.paused }?.deviceId
                ?: snap.items.first().deviceId
        }
    }

    private fun hasAuthCredentials(context: Context): Boolean = runBlocking {
        val prefs = BockMediaApp.get(context).preferences
        val token = prefs.mobileToken.first()?.trim().orEmpty()
        val user = prefs.adminUser.first()?.trim().orEmpty()
        val pass = prefs.adminPass.first()?.trim().orEmpty()
        token.isNotEmpty() || (user.isNotEmpty() && pass.isNotEmpty())
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
        val appContext = context.applicationContext
        Thread {
            repeat(POLL_ATTEMPTS) {
                Thread.sleep(POLL_INTERVAL_MS)
                fetchAndStore(appContext)
                val item = NowPlayingSessionStore.snapshot?.items?.find { it.deviceId == deviceId }
                if (item != null && (item.track != prevTrack || item.filepath != prevPath)) {
                    NowPlayingWidget.publishSession(appContext)
                    return@Thread
                }
            }
            NowPlayingWidget.publishSession(appContext)
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
}
