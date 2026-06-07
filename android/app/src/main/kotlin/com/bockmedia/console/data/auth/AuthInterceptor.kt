package com.bockmedia.console.data.auth

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/** Sends mobile Bearer token and/or admin Basic auth for external hosts only. */
class BockAuthInterceptor(
    private val localHostsProvider: () -> Set<String>,
    private val usernameProvider: () -> String?,
    private val passwordProvider: () -> String?,
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host.lowercase()
        if (host in localHostsProvider()) {
            return chain.proceed(chain.request())
        }
        val token = tokenProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val user = usernameProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val pass = passwordProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val builder = chain.request().newBuilder()
        when {
            token != null && user != null && pass != null -> {
                builder.header("Authorization", Credentials.basic(user, pass))
                builder.header("X-BockMedia-Token", token)
            }
            token != null -> builder.header("Authorization", "Bearer $token")
            user != null && pass != null -> builder.header("Authorization", Credentials.basic(user, pass))
        }
        return chain.proceed(builder.build())
    }
}

class TunnelBlockedException : Exception("API blocked on public URL — use LAN or VPN")
