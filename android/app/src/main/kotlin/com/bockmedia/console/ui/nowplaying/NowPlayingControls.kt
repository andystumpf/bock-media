package com.bockmedia.console.ui.nowplaying

import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.media.isLocalPhoneDevice

fun resolveSerial(device: NowPlayingDeviceItem, alexaDevices: List<AlexaDevice>): String? {
    val name = device.deviceName?.lowercase() ?: return null
    return alexaDevices.firstOrNull { (it.name ?: "").lowercase() == name }?.serial
}

fun canControlDevice(
    device: NowPlayingDeviceItem,
    alexaDevices: List<AlexaDevice>,
    controlsAvailable: Boolean,
    remoteOk: Boolean,
): Boolean {
    if (isLocalPhoneDevice(device.deviceId)) return true
    if (!controlsAvailable || !remoteOk) return false
    if (device.deviceId.startsWith("msp-")) return false
    if (device.deviceName.isNullOrBlank()) return false
    return resolveSerial(device, alexaDevices) != null
}
