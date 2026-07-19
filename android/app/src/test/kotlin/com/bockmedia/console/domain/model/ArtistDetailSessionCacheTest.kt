package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.ArtistDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ArtistDetailSessionCacheTest {
    @Before
    fun clear() {
        ArtistDetailSessionCache.clear()
    }

    @Test
    fun storesAndRetrievesDetail() {
        val detail = ArtistDetailResponse(artist = "Radiohead", trackCount = 42)
        ArtistDetailSessionCache.put("Radiohead", detail, "/art.jpg")
        val cached = ArtistDetailSessionCache.get("Radiohead")
        assertEquals("Radiohead", cached?.first?.artist)
        assertEquals(42, cached?.first?.trackCount)
        assertEquals("/art.jpg", cached?.second)
    }

    @Test
    fun missesUnknownArtist() {
        assertNull(ArtistDetailSessionCache.get("Unknown"))
    }
}
