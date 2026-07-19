package com.bockmedia.console.data.repository

import com.bockmedia.console.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class PeekBaseUrlTest {

    @Test
    fun peekBaseUrl_onWifi_prefersLanOverStaleExternalCache() {
        val local = "http://your-server.local:3001"
        val external = "http://142.56.8.193:3001"
        val result = peekEndpoint("http://142.56.8.193:3001", local, external, wifi = true)
        assertEquals(local, result)
    }

    @Test
    fun peekBaseUrl_onCellular_prefersExternalOverLanCache() {
        val local = "http://your-server.local:3001"
        val external = "http://142.56.8.193:3001"
        val result = peekEndpoint(local, local, external, wifi = false)
        assertEquals(external, result)
    }

    /** Mirrors [BockMediaRepository.peekBaseUrl] selection without constructing the repository. */
    private fun peekEndpoint(
        cached: String?,
        local: String,
        external: String,
        wifi: Boolean,
    ): String? {
        val localNorm = AppPreferences.normalizeUrl(local)
        val externalNorm = AppPreferences.normalizeUrl(external)
        if (!wifi) {
            if (cached != null && AppPreferences.isLanHost(cached, localNorm, externalNorm)) {
                return externalNorm ?: cached
            }
            return externalNorm ?: cached ?: localNorm
        }
        if (cached != null && AppPreferences.isLanHost(cached, localNorm, externalNorm)) {
            return cached
        }
        return localNorm
            ?: cached?.takeIf { AppPreferences.isLanHost(it, localNorm, externalNorm) }
    }
}
