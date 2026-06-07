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
    fun localHosts_includesLocalHostOnly() {
        val hosts = AppPreferences.localHosts("http://192.168.1.187:3001", "http://142.56.8.193:3001")
        assertTrue(hosts.contains("192.168.1.187"))
        assertFalse(hosts.contains("142.56.8.193"))
    }
}
