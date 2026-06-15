package com.bockmedia.console.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEndpointResolverTest {

    @Test
    fun pickEndpoint_prefersLanWhenBothReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.187:3001",
            external = "http://142.56.8.193:3001",
            localReachable = true,
            externalReachable = true,
        )
        assertEquals("http://192.168.1.187:3001", url)
    }

    @Test
    fun pickEndpoint_usesExternalWhenOnlyExternalReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.187:3001",
            external = "http://142.56.8.193:3001",
            localReachable = false,
            externalReachable = true,
        )
        assertEquals("http://142.56.8.193:3001", url)
    }

    @Test
    fun pickEndpoint_prefersLanFallbackWhenNeitherReachable() {
        val url = ServerEndpointResolver.pickEndpoint(
            local = "http://192.168.1.187:3001",
            external = "http://142.56.8.193:3001",
            localReachable = false,
            externalReachable = false,
        )
        assertEquals("http://192.168.1.187:3001", url)
    }
}
