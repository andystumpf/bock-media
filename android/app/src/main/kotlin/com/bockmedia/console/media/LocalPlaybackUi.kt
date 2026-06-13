package com.bockmedia.console.media

import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.api.dto.UpcomingTrack

const val LOCAL_PHONE_DEVICE_ID = "local-phone"

fun isLocalPhoneDevice(deviceId: String): Boolean = deviceId == LOCAL_PHONE_DEVICE_ID

fun LocalPlaybackState.toNowPlayingDevice(): NowPlayingDeviceItem? {
    val track = current ?: return null
    return NowPlayingDeviceItem(
        deviceId = LOCAL_PHONE_DEVICE_ID,
        deviceName = "This phone",
        track = track.title,
        artist = track.artist,
        album = track.album,
        filepath = track.path,
        duration_ms = durationMs,
        offset_ms = positionMs,
        paused = !isPlaying,
        upcoming = tracks.drop(index + 1).take(15).map { t ->
            UpcomingTrack(title = t.title, artist = t.artist, path = t.path)
        },
    )
}
