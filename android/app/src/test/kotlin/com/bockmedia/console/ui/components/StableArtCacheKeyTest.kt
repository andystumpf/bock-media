package com.bockmedia.console.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StableArtCacheKeyTest {

    @Test
    fun lanAndExternalHostsShareOneKey() {
        val lan = stableArtCacheKey("http://192.168.1.187:3001/artwork/Music/a%20b/cover.jpg")
        val ext = stableArtCacheKey("http://142.56.8.193:3001/artwork/Music/a%20b/cover.jpg")
        assertEquals(lan, ext)
        assertEquals("/artwork/Music/a%20b/cover.jpg", lan)
    }

    @Test
    fun queryIsPreserved() {
        val key = stableArtCacheKey("http://host:3001/artwork/x.jpg?v=2")
        assertEquals("/artwork/x.jpg?v=2", key)
    }

    @Test
    fun nonUrlReturnedAsIs() {
        assertEquals("just-a-key", stableArtCacheKey("just-a-key"))
    }

    @Test
    fun nullModelYieldsEmpty() {
        assertEquals("", stableArtCacheKey(null))
    }
}
