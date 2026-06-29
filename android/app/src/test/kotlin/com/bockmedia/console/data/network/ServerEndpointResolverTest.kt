package com.bockmedia.console.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEndpointResolverTest {

    @Test
    fun pickEndpoint_prefersLanWhenBothReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.100:3001",
            external = "http://203.0.113.10:3001",
            localReachable = true,
            externalReachable = true,
        )
        assertEquals("http://192.168.1.100:3001", url)
    }

    @Test
    fun pickEndpoint_usesExternalWhenOnlyExternalReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.100:3001",
            external = "http://203.0.113.10:3001",
            localReachable = false,
            externalReachable = true,
        )
        assertEquals("http://203.0.113.10:3001", url)
    }

    @Test
    fun pickEndpoint_prefersLanFallbackWhenNeitherReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.100:3001",
            external = "http://203.0.113.10:3001",
            localReachable = false,
            externalReachable = false,
        )
        assertEquals("http://192.168.1.100:3001", url)
    }

    @Test
    fun pickEndpoint_onCellular_usesExternalEvenWhenUnreachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.100:3001",
            external = "http://203.0.113.10:3001",
            localReachable = false,
            externalReachable = false,
            wifiAvailable = false,
        )
        assertEquals("http://203.0.113.10:3001", url)
    }
}
