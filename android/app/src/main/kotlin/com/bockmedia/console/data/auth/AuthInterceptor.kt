package com.bockmedia.console.data.auth

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val usernameProvider: () -> String?,
    private val passwordProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val user = usernameProvider()
        val pass = passwordProvider()
        val request = if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("Authorization", Credentials.basic(user, pass))
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

class MobileTokenInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(request)
    }
}

class TunnelBlockedException : Exception("API blocked on public URL — use LAN or VPN")
