package com.bockmedia.console.ui

import com.bockmedia.console.data.api.dto.AlexaRemoteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlexaRemoteStatusTest {
    @Test
    fun effectiveLoginUrl_prefersLoginUrlThenUrlThenHostPort() {
        assertEquals(
            "http://a:1",
            AlexaRemoteStatus(loginUrl = "http://a:1").effectiveLoginUrl(),
        )
        assertEquals(
            "http://b:2",
            AlexaRemoteStatus(url = "http://b:2").effectiveLoginUrl(),
        )
        assertEquals(
            "http://c:3005",
            AlexaRemoteStatus(host = "c", port = 3005).effectiveLoginUrl(),
        )
    }

    @Test
    fun effectiveLoginStatus_mergesStatusFields() {
        assertEquals("waiting", AlexaRemoteStatus(status = "waiting").effectiveLoginStatus())
        assertEquals("starting", AlexaRemoteStatus(loginStatus = "starting").effectiveLoginStatus())
    }

    @Test
    fun alexaControlsAvailable_requiresConfiguredAndAuthenticated() {
        assertTrue(alexaControlsAvailable(AlexaRemoteStatus(configured = true, authenticated = true)))
        assertTrue(!alexaControlsAvailable(AlexaRemoteStatus(configured = true, authenticated = false)))
    }
}
