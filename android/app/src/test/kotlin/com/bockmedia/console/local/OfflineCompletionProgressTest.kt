package com.bockmedia.console.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Pure logic tests for offline completion without Android Context.
 */
class OfflineCompletionProgressTest {

    @Test
    fun completionProgress_countsOnlyFilesOnDisk() {
        val manifest = OfflineCollectionManifest(
            id = "pl-test",
            title = "Test",
            tracks = listOf(
                OfflineTrackEntry("/a.mp3", "A", fileName = "0000_a.mp3"),
                OfflineTrackEntry("/b.mp3", "B", fileName = "0001_b.mp3"),
            ),
        )
        val dir = createTempDir("offline-test")
        File(dir, "0000_a.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val progress = completionProgressForTest(manifest, dir)
        assertEquals(0.5f, progress, 0.001f)
        assertFalse(isCollectionCompleteForTest(manifest, dir))
    }

    @Test
    fun isCollectionComplete_whenAllTrackFilesExist() {
        val manifest = OfflineCollectionManifest(
            id = "pl-test",
            title = "Test",
            tracks = listOf(
                OfflineTrackEntry("/a.mp3", "A", fileName = "0000_a.mp3"),
            ),
        )
        val dir = createTempDir("offline-test")
        File(dir, "0000_a.mp3").writeBytes(byteArrayOf(1))
        assertEquals(1f, completionProgressForTest(manifest, dir), 0.001f)
        assert(isCollectionCompleteForTest(manifest, dir))
    }

    private fun completionProgressForTest(manifest: OfflineCollectionManifest, dir: File): Float {
        if (manifest.tracks.isEmpty()) return 0f
        val done = manifest.tracks.count { entry -> File(dir, entry.fileName).exists() && File(dir, entry.fileName).length() > 0 }
        return done.toFloat() / manifest.tracks.size
    }

    private fun isCollectionCompleteForTest(manifest: OfflineCollectionManifest, dir: File): Boolean =
        manifest.tracks.isNotEmpty() && manifest.tracks.all {
            File(dir, it.fileName).exists() && File(dir, it.fileName).length() > 0
        }
}
