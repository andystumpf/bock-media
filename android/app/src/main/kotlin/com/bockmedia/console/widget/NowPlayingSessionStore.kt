package com.bockmedia.console.widget

import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.domain.model.PlaybackFocus

object NowPlayingSessionStore {
    data class Snapshot(
        val items: List<NowPlayingDeviceItem>,
        val controlsAvailable: Boolean,
        val baseUrl: String?,
        /** Lowercase Alexa device name → serial for remote control. */
        val alexaSerialByName: Map<String, String> = emptyMap(),
        val remoteOk: Boolean = false,
        val updatedAtMs: Long = System.currentTimeMillis(),
    )

    @Volatile
    var snapshot: Snapshot? = null

    @Volatile
    var focusedDeviceId: String? = null

    fun items(): List<NowPlayingDeviceItem> = snapshot?.items.orEmpty()

    fun focusedItem(): NowPlayingDeviceItem? {
        val snap = snapshot ?: return null
        if (snap.items.isEmpty()) return null
        focusedDeviceId = PlaybackFocus.focusedDeviceId ?: focusedDeviceId
        return PlaybackFocus.resolveFocusedItem(snap.items)
            ?: focusedDeviceId?.let { id -> snap.items.find { it.deviceId == id } }
            ?: snap.items.firstOrNull { !it.paused }
            ?: snap.items.firstOrNull()
    }

    fun cycleFocus(forward: Boolean = true) {
        val snap = snapshot ?: return
        val list = snap.items
        if (list.size <= 1) return
        val currentId = focusedDeviceId ?: focusedItem()?.deviceId
        val idx = list.indexOfFirst { it.deviceId == currentId }.takeIf { it >= 0 } ?: 0
        val next = if (forward) (idx + 1) % list.size else (idx - 1 + list.size) % list.size
        focusedDeviceId = list[next].deviceId
    }

    fun resolveSerial(deviceName: String?): String? {
        if (deviceName.isNullOrBlank()) return null
        return snapshot?.alexaSerialByName?.get(deviceName.lowercase())
    }

    fun canControl(item: NowPlayingDeviceItem): Boolean {
        val snap = snapshot ?: return false
        if (!snap.controlsAvailable || !snap.remoteOk) return false
        if (item.deviceId.startsWith("msp-")) return false
        if (item.deviceName.isNullOrBlank()) return false
        return resolveSerial(item.deviceName) != null
    }
}
