package com.bockmedia.console.smoke

import android.content.Context
import android.os.SystemClock
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.data.api.dto.HouseholdMember
import com.bockmedia.console.local.ClientIdStore
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertTrue
import com.bockmedia.console.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Server-backed helpers for mobile UI tests (extends profile prefs API). */
object MobileTestApi {
    private const val ARTIFACT_PREFIX = "uitest-"

    fun householdMembers(context: Context): List<HouseholdMember> =
        ProfileTestApi.householdMembers(context)

    fun localContinueAfterQueue(context: Context): String =
        ProfileTestApi.localContinueAfterQueue(context)

    fun localWifiOnly(context: Context): Boolean =
        ProfileTestApi.localWifiOnly(context)

    fun continueAfterQueueForMember(context: Context, memberId: String): String? =
        ProfileTestApi.continueAfterQueueForMember(context, memberId)

    fun wifiOnlyForMember(context: Context, memberId: String): Boolean? =
        ProfileTestApi.wifiOnlyForMember(context, memberId)

    fun memberTotalPlays(context: Context, memberId: String): Int =
        ProfileTestApi.memberTotalPlays(context, memberId)

    fun switchToMember(context: Context, memberId: String?) =
        ProfileTestApi.switchToMember(context, memberId)

    fun flushPrefs(context: Context) =
        ProfileTestApi.flushPrefs(context)

    fun waitForRemoteContinue(context: Context, memberId: String, expected: String, timeoutMs: Long = 15_000) =
        ProfileTestApi.waitForRemoteContinue(context, memberId, expected, timeoutMs)

    fun assertRemoteContinue(context: Context, memberId: String, expected: String) =
        ProfileTestApi.assertRemoteContinue(context, memberId, expected)

    fun serverBaseUrl(context: Context): String = runBlocking(Dispatchers.IO) {
        runCatching {
            BockMediaApp.get(context).repository.let { repo ->
                // Uses active endpoint after cold boot.
                repo.testConnection()
                BuildConfig.DEFAULT_LOCAL_SERVER_URL.takeIf { it.isNotBlank() }
                    ?: BuildConfig.DEFAULT_EXTERNAL_SERVER_URL
            }
        }.getOrElse {
            BuildConfig.DEFAULT_LOCAL_SERVER_URL.takeIf { it.isNotBlank() }
                ?: BuildConfig.DEFAULT_EXTERNAL_SERVER_URL
        }
    }

    fun healthOk(context: Context): Boolean = runBlocking(Dispatchers.IO) {
        runCatching {
            val url = URL("${serverBaseUrl(context).trimEnd('/')}/api/health")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            conn.responseCode == 200
        }.getOrDefault(false)
    }

    fun resolveFixturePlaylistId(context: Context, preferredName: String = "UITest Small"): String? =
        runBlocking(Dispatchers.IO) {
            runCatching {
                val items = BockMediaApp.get(context).repository.playlists(
                    search = preferredName,
                    limit = 5,
                ).items
                items.firstOrNull()?.id
                    ?: BockMediaApp.get(context).repository.playlists(limit = 1).items.firstOrNull()?.id
            }.getOrNull()
        }

    fun teardownSweep(context: Context) = runBlocking(Dispatchers.IO) {
        val repo = BockMediaApp.get(context).repository
        runCatching {
            val automations = repo.automations().items
            automations.filter { (it.name ?: "").startsWith(ARTIFACT_PREFIX) }
                .forEach { runCatching { repo.deleteAutomation(it.id) } }
        }
        runCatching {
            val playlists = repo.playlists(search = ARTIFACT_PREFIX, limit = 100).items
            playlists.forEach { runCatching { repo.deletePlaylist(it.id) } }
        }
    }

    fun uiTestMember(context: Context): HouseholdMember? =
        householdMembers(context).find {
            it.name.equals("UI Test", ignoreCase = true) ||
                it.id.contains("uitest", ignoreCase = true)
        }

    fun assertHealthWatchdog(context: Context) {
        val url = URL("${serverBaseUrl(context).trimEnd('/')}/api/health")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        assertTrue("health HTTP", conn.responseCode == 200)
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        assertTrue("watchdogFresh", json.optBoolean("watchdogFresh", true))
    }

    fun fixtureGenreName(): String = "Rock"
    fun fixtureArtistName(): String = "Gillian Welch"
    fun fixtureAlbumTitle(): String = "Soul Journey"
    fun fixtureAlbumArtist(): String = "Gillian Welch"

    fun assertInjectedHomeFailure(context: Context) {
        val url = URL("${serverBaseUrl(context).trimEnd('/')}/api/home?deferred=1&playlistLimit=5")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("X-UITest-Fail", "home")
        val token = BuildConfig.DEFAULT_MOBILE_API_TOKEN.trim()
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        assertTrue("injected home failure", conn.responseCode == 500)
    }
}
