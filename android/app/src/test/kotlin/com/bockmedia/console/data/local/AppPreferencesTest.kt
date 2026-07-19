package com.bockmedia.console.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun normalizeUrl_addsHttp() {
        assertTrue(AppPreferences.isValidUrl("192.168.1.100:3001"))
        assertTrue(AppPreferences.normalizeUrl("192.168.1.100:3001").startsWith("http://"))
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
    fun streamUrl_includesBitrateBeforeSigning() {
        val url = AppPreferences.streamUrl(
            "http://host:3001",
            "/mnt/music/a/track.flac",
            title = "Song",
            bitrateKbps = 192,
            mediaSignSecret = "test-token",
        )
        assertTrue(url!!.contains("br=192"))
        assertTrue(url.contains("sig="))
    }

    @Test
    fun serverOrigin_stripsAppPathSuffix() {
        assertEquals(
            "http://your-server.local:3001",
            AppPreferences.serverOrigin("http://your-server.local:3001/app"),
        )
    }

    @Test
    fun libraryPathForStream_rejectsBareTitle() {
        assertFalse(AppPreferences.isValidLibraryPath("Home"))
        assertFalse(AppPreferences.isValidLibraryPath("Magic"))
        assertNull(AppPreferences.libraryPathForStream("Losing My Religion"))
    }

    @Test
    fun libraryPathForStream_acceptsNasPath() {
        val path = AppPreferences.libraryPathForStream(
            "/mnt/bock/Music/plexDB/Aktive/Home/Home.mp3",
        )
        assertEquals("/mnt/bock/Music/plexDB/Aktive/Home/Home.mp3", path)
        val url = AppPreferences.streamUrl("http://host:3001/app", path)
        assertTrue(url!!.startsWith("http://host:3001/stream/mnt/bock/Music/"))
    }
}
