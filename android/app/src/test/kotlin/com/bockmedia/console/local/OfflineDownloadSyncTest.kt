package com.bockmedia.console.local

import com.bockmedia.console.domain.model.PlayTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineDownloadSyncTest {
    @Test
    fun encodeDecode_roundTrip() {
        val records = listOf(
            OfflineDownloadRecord("pl-rock", "Rock Mix", "playlist", "rock"),
            OfflineDownloadRecord("artist-Led%20Zeppelin", "Led Zeppelin", "artist", null),
        )
        val raw = OfflineDownloadSync.encode(records)
        val decoded = OfflineDownloadSync.decode(raw)
        assertEquals(records, decoded)
    }

    @Test
    fun playTarget_toRecord_usesDownloadId() {
        val record = PlayTarget.Playlist("abc", "My Mix").toOfflineDownloadRecord()
        assertEquals("pl-abc", record.id)
        assertEquals("My Mix", record.title)
        assertEquals("abc", record.sourcePlaylistId)
    }
}
