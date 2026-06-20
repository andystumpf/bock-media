package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.media.LOCAL_PHONE_DEVICE_ID
import com.bockmedia.console.media.isLocalPhoneDevice

/** Remembers which speaker the user last started playback on. */
object PlaybackFocus {
    @Volatile
    var pendingDeviceValue: String? = null
        private set

    @Volatile
    var pendingDeviceLabel: String? = null
        private set

    @Volatile
    var focusedDeviceId: String? = null
        private set

    @Volatile
    var generation: Int = 0
        private set

    fun notePlayStarted(deviceValue: String, deviceLabel: String?) {
        if (deviceValue == LOCAL_PHONE_DEVICE_ID || isLocalPhoneDevice(deviceValue)) {
            focusedDeviceId = LOCAL_PHONE_DEVICE_ID
            pendingDeviceValue = null
            pendingDeviceLabel = null
            generation++
            return
        }
        pendingDeviceValue = deviceValue.trim().takeIf { it.isNotEmpty() }
        pendingDeviceLabel = deviceLabel?.trim()?.takeIf { it.isNotEmpty() }
        generation++
    }

    fun clear() {
        pendingDeviceValue = null
        pendingDeviceLabel = null
        focusedDeviceId = null
    }

    fun syncPendingFocus(items: List<NowPlayingDeviceItem>, alexaDevices: List<AlexaDevice> = emptyList()): Boolean {
        val deviceValue = pendingDeviceValue ?: return false
        val label = pendingDeviceLabel

        val match = findMatchingItem(items, deviceValue, label, alexaDevices) ?: return false
        focusedDeviceId = match.deviceId
        pendingDeviceValue = null
        pendingDeviceLabel = null
        generation++
        return true
    }

    fun resolveFocusedItem(
        items: List<NowPlayingDeviceItem>,
        alexaDevices: List<AlexaDevice> = emptyList(),
    ): NowPlayingDeviceItem? {
        if (items.isEmpty()) return null
        syncPendingFocus(items, alexaDevices)
        focusedDeviceId?.let { id -> items.find { it.deviceId == id }?.let { return it } }
        return items.firstOrNull { !it.paused } ?: items.firstOrNull()
    }

    fun focusedIndex(items: List<NowPlayingDeviceItem>): Int {
        if (focusedDeviceId == LOCAL_PHONE_DEVICE_ID) {
            val idx = items.indexOfFirst { isLocalPhoneDevice(it.deviceId) }
            if (idx >= 0) return idx
        }
        val id = focusedDeviceId ?: return 0
        return items.indexOfFirst { it.deviceId == id }.coerceAtLeast(0)
    }

    private fun findMatchingItem(
        items: List<NowPlayingDeviceItem>,
        deviceValue: String,
        label: String?,
        alexaDevices: List<AlexaDevice>,
    ): NowPlayingDeviceItem? {
        label?.let { matchByName(items, it) }?.let { return it }

        if (!deviceValue.startsWith(GROUP_PREFIX)) {
            alexaDevices.firstOrNull { it.serial == deviceValue }?.name?.let { alexaName ->
                matchByName(items, alexaName)?.let { return it }
            }
        }

        return null
    }

    private fun matchByName(items: List<NowPlayingDeviceItem>, name: String): NowPlayingDeviceItem? {
        items.find { it.deviceName.equals(name, ignoreCase = true) }?.let { return it }
        return items.find { item ->
            val deviceName = item.deviceName ?: return@find false
            deviceName.contains(name, ignoreCase = true) || name.contains(deviceName, ignoreCase = true)
        }
    }

    private const val GROUP_PREFIX = "group:"
}
