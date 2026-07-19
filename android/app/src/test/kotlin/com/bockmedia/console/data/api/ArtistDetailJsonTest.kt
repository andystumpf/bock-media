package com.bockmedia.console.data.api

import com.bockmedia.console.data.api.dto.ArtistDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistDetailJsonTest {

    @Test
    fun decodeArtistDetail_acceptsFloatDurationSeconds() {
        val json = """
            {
              "artist": "10cc",
              "trackCount": 1,
              "albumCount": 1,
              "totalPlays": 0,
              "followed": false,
              "rating": 0,
              "topTracks": [{
                "title": "I'm Not in Love",
                "path": "/music/10cc/song.mp3",
                "duration_seconds": 366.0,
                "playCount": 0,
                "liked": false,
                "rating": 0
              }],
              "albums": [],
              "similarArtists": [],
              "appearsOn": []
            }
        """.trimIndent()
        val detail = bockJson.decodeFromString(ArtistDetailResponse.serializer(), json)
        assertEquals("10cc", detail.artist)
        assertEquals(366.0, detail.topTracks.first().durationSeconds)
    }
}
