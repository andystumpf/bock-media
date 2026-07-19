package com.bockmedia.console.data.auth

import com.bockmedia.console.ui.testing.UITestState
import okhttp3.Interceptor
import okhttp3.Response

/** Adds X-UITest-Fail when UI-test state requests injected API failures. */
class UITestFailInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val fail = UITestState.failEndpoint
        val request = if (!fail.isNullOrBlank()) {
            chain.request().newBuilder()
                .header("X-UITest-Fail", fail)
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
