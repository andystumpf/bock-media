package com.bockmedia.console.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.DownloadState
import com.bockmedia.console.local.OFFLINE_LOCAL_MEMBER_KEY
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.local.OfflineDownloadSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies offline download registration and visibility without a household profile
 * (the common emulator / fresh-install case that previously made downloads appear dead).
 */
@RunWith(AndroidJUnit4::class)
class OfflineDownloadSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Before
    fun clearLocalRegistry() {
        OfflineDownloadSync.resetRegistryForTests(context)
        ActiveProfileStore.chooseUnattributed(context)
        OfflineDownloadManager.refresh(context)
    }

    @Test
    fun register_withoutProfile_usesLocalBucketAndIsVisible() {
        val target = PlayTarget.Playlist("smoke-pl", "Smoke Playlist")
        OfflineDownloadSync.register(context, target)
        val ids = OfflineDownloadSync.visibleCollectionIds(context)
        assertTrue("Download should be visible without a household profile", ids.contains("pl-smoke-pl"))
        val records = OfflineDownloadSync.recordsForMember(context, OFFLINE_LOCAL_MEMBER_KEY)
        assertEquals(1, records.size)
        assertEquals("pl-smoke-pl", records.first().id)
    }

    @Test
    fun download_withoutProfile_surfacesDownloadingStatus() {
        val target = PlayTarget.Playlist("smoke-dl", "Smoke Download")
        OfflineDownloadManager.download(context, target)
        Thread.sleep(500)
        val status = OfflineDownloadManager.statusFor(context, target)
        assertTrue(
            "Status should be visible (downloading or failed — not null)",
            status != null,
        )
        assertTrue(
            "Expected downloading or failed, got ${status?.state}",
            status?.state == DownloadState.Downloading || status?.state == DownloadState.Failed,
        )
    }
}
