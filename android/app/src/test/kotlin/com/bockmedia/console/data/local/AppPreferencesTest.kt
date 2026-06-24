package com.bockmedia.console.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun normalizeUrl_addsHttp() {
        assertTrue(AppPreferences.isValidUrl("192.168.1.187:3001"))
        assertTrue(AppPreferences.normalizeUrl("192.168.1.187:3001").startsWith("http://"))
    }

    @Test
    fun isValidUrl_rejectsBlank() {
        assertFalse(AppPreferences.isValidUrl(""))
        assertFalse(AppPreferences.isValidUrl("   "))
    }

    @Test
    fun artworkUrl_encodesPath() {
        val url = AppPreferences.artworkUrl("http://host:3001", "/mnt/music/a b/track.mp3")
        assertTrue(url!!.contains("a%20b"))
    }

    @Test
    fun streamUrl_includesTitleAndArtistQuery() {
        val url = AppPreferences.streamUrl(
            "http://host:3001",
            "/mnt/music/a/track.mp3",
            title = "Already Gone",
            artist = "Eagles",
        )
        assertTrue(url!!.contains("title=Already%20Gone"))
        assertTrue(url.contains("artist=Eagles"))
    }
}
