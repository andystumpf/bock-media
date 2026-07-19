package com.bockmedia.console.data.local

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlSignerTest {

    @Test
    fun appendMediaSig_addsExpiresAndSig() {
        val signed = MediaUrlSigner.appendMediaSig(
            path = "/stream/mnt/Music/a.mp3",
            query = mapOf("title" to "Song"),
            secret = "test-token",
        )
        assertTrue(signed.contains("expires="))
        assertTrue(signed.contains("sig="))
        assertTrue(signed.contains("title=Song"))
    }
}
