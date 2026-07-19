package com.bockmedia.console.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.bockmedia.console.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phase 6c — versionName matches latest app-release-notes.json entry. */
@RunWith(AndroidJUnit4::class)
@SmallTest
class VersionConsistencyTest {
    @Test fun versionMatchesReleaseNotes() {
        val versionName = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        val repoRoot = File(System.getProperty("user.dir")).let { dir ->
            generateSequence(dir) { it.parentFile }.firstOrNull {
                File(it, "app-release-notes.json").isFile
            } ?: dir
        }
        val notes = JSONObject(File(repoRoot, "app-release-notes.json").readText())
        val latest = notes.getJSONArray("releases").getJSONObject(0).getString("version")
        assertEquals("versionName must match latest release notes", latest, versionName)
    }
}
