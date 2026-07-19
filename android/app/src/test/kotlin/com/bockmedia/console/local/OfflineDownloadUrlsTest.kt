package com.bockmedia.console.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadUrlsTest {
    @Test
    fun offlineTrackFileName_usesMp3ForFlacSources() {
        val name = offlineTrackFileName("/music/Artist/Album/track.flac", 0, transcodeToMp3 = true)
        assertTrue(name.endsWith(".mp3"))
        assertTrue(!name.endsWith(".flac"))
    }

    @Test
    fun offlineTrackFileName_keepsNativeExtensionForMp3() {
        val name = offlineTrackFileName("/music/Artist/Album/track.mp3", 0, transcodeToMp3 = false)
        assertTrue(name.endsWith(".mp3"))
    }
}
