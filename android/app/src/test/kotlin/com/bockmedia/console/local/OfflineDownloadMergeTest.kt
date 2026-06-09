package com.bockmedia.console.local

import com.bockmedia.console.domain.model.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadMergeTest {

    @Test
    fun offlineTrackFileName_differsForSameBasenameDifferentPaths() {
        val a = offlineTrackFileName("/albumA/song.mp3", 0)
        val b = offlineTrackFileName("/albumB/song.mp3", 0)
        assertNotEquals(a, b)
        assertTrue(a.endsWith("_song.mp3"))
        assertTrue(b.endsWith("_song.mp3"))
    }

    @Test
    fun mergeTrackEntries_preservesExistingFileNamesAndAddsNewTracks() {
        val existing = OfflineCollectionManifest(
            id = "pl-test",
            title = "Test",
            tracks = listOf(
                OfflineTrackEntry("/music/one.mp3", "One", fileName = "0000_one.mp3"),
            ),
        )
        val merged = mergeTracksForTest(
            existing,
            listOf(
                LocalTrack("/music/one.mp3", "One"),
                LocalTrack("/music/two.mp3", "Two"),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("0000_one.mp3", merged.first { it.path == "/music/one.mp3" }.fileName)
        assertTrue(merged.any { it.path == "/music/two.mp3" })
    }

    @Test
    fun mergeTrackEntries_assignsDistinctFileNamesForSameBasename() {
        val merged = mergeTracksForTest(
            existing = null,
            resolved = listOf(
                LocalTrack("/albumA/song.mp3", "A"),
                LocalTrack("/albumB/song.mp3", "B"),
            ),
        )
        assertEquals(2, merged.map { it.fileName }.toSet().size)
    }

    @Test
    fun mergeTrackEntries_reassignsDuplicateLegacyFileNames() {
        val existing = OfflineCollectionManifest(
            id = "pl-test",
            title = "Test",
            tracks = listOf(
                OfflineTrackEntry("/albumA/song.mp3", "A", fileName = "0000_song.mp3"),
                OfflineTrackEntry("/albumB/song.mp3", "B", fileName = "0000_song.mp3"),
            ),
        )
        val merged = mergeTracksForTest(existing, emptyList())
        assertEquals(2, merged.map { it.fileName }.toSet().size)
    }

    private fun mergeTracksForTest(
        existing: OfflineCollectionManifest?,
        resolved: List<LocalTrack>,
    ): List<OfflineTrackEntry> {
        val byPath = linkedMapOf<String, OfflineTrackEntry>()
        val usedNames = mutableSetOf<String>()
        existing?.tracks?.forEach {
            val fileName = if (it.fileName in usedNames) {
                uniqueOfflineFileName(it.path, usedNames.size, usedNames)
            } else {
                usedNames.add(it.fileName)
                it.fileName
            }
            byPath[it.path] = it.copy(fileName = fileName)
        }
        var seq = existing?.tracks?.size ?: 0
        resolved.forEach { track ->
            if (track.path.isBlank()) return@forEach
            val prior = byPath[track.path]
            val fileName = prior?.fileName ?: uniqueOfflineFileName(track.path, seq, usedNames).also { seq++ }
            byPath[track.path] = OfflineTrackEntry(
                path = track.path,
                title = track.title,
                artist = track.artist,
                album = track.album,
                fileName = fileName,
            )
        }
        return byPath.values.toList()
    }
}
