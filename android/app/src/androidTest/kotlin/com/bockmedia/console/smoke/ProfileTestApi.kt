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
import org.junit.Assert.assertEquals

/** Server-backed checks for profile preference tests on a connected device. */
object ProfileTestApi {
    fun householdMembers(context: Context): List<HouseholdMember> = runBlocking(Dispatchers.IO) {
        BockMediaApp.get(context).repository.household().members
    }

    fun localContinueAfterQueue(context: Context): String = runBlocking(Dispatchers.IO) {
        BockMediaApp.get(context).preferences.getContinueAfterQueueSync()
    }

    fun localWifiOnly(context: Context): Boolean = runBlocking(Dispatchers.IO) {
        BockMediaApp.get(context).preferences.isDownloadWifiOnlySync()
    }

    fun continueAfterQueueForMember(context: Context, memberId: String): String? = runBlocking(Dispatchers.IO) {
        val app = BockMediaApp.get(context)
        val remote = app.repository.clientPrefs(ClientIdStore.clientId(context), memberId)
        remote.memberPrefs["continueAfterQueue"]?.jsonPrimitive?.content
            ?: remote.merged["continueAfterQueue"]?.jsonPrimitive?.content
    }

    fun wifiOnlyForMember(context: Context, memberId: String): Boolean? = runBlocking(Dispatchers.IO) {
        val app = BockMediaApp.get(context)
        val remote = app.repository.clientPrefs(ClientIdStore.clientId(context), memberId)
        remote.memberPrefs["downloadWifiOnly"]?.jsonPrimitive?.booleanOrNull
            ?: remote.merged["downloadWifiOnly"]?.jsonPrimitive?.booleanOrNull
    }

    fun memberTotalPlays(context: Context, memberId: String): Int {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            if (attempt > 0) Thread.sleep(2_000L * attempt)
            runCatching {
                return runBlocking(Dispatchers.IO) {
                    BockMediaApp.get(context).repository.analytics(
                        member = memberId,
                        householdWide = false,
                    ).totalPlays
                }
            }.onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("analytics failed")
    }

    fun switchToMember(context: Context, memberId: String?) = runBlocking(Dispatchers.IO) {
        val previous = ActiveProfileStore.activeMemberId(context)
        ClientPrefsSync.onActiveMemberChanged(context, memberId, previous)
    }

    fun flushPrefs(context: Context) = runBlocking(Dispatchers.IO) {
        ClientPrefsSync.push(context)
    }

    fun waitForRemoteContinue(
        context: Context,
        memberId: String,
        expected: String,
        timeoutMs: Long = 15_000,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (continueAfterQueueForMember(context, memberId) == expected) return
            runBlocking(Dispatchers.IO) { ClientPrefsSync.push(context) }
            Thread.sleep(400)
        }
        assertRemoteContinue(context, memberId, expected)
    }

    fun assertRemoteContinue(context: Context, memberId: String, expected: String) {
        val actual = continueAfterQueueForMember(context, memberId)
        assertEquals("continueAfterQueue for $memberId", expected, actual)
    }
}
