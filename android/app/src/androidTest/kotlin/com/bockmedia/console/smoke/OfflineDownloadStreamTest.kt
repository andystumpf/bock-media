package com.bockmedia.console.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.local.AppPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hits the real NAS /stream/ endpoint for one library path (device must be on home LAN). */
@RunWith(AndroidJUnit4::class)
class OfflineDownloadStreamTest {

    @Test
    fun streamUrl_downloadsKnownTrackFromNas() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val app = BockMediaApp.get(context)
        app.preferences.applyBuildDefaultsIfEmpty()
        val base = app.repository.peekBaseUrl() ?: app.resolveBaseUrl()
        val path = "/mnt/bock/Music/plexDB/Aktive/Home/Home.mp3"
        val url = AppPreferences.streamUrl(
            base = base,
            filepath = path,
            title = "Home",
            artist = "Aktive",
            bitrateKbps = 192,
            mediaSignSecret = app.preferences.mobileTokenNow(),
        ) ?: error("Failed to build stream URL")
        assertTrue("Stream base must not include /app path", !url.contains("/app/stream/"))
        val client = app.buildLiveAuthHttpClient(readTimeoutSec = 120)
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertTrue("Expected HTTP 200 for $path but got ${response.code} from $base", response.isSuccessful)
        }
    }
}
