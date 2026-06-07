package com.bockmedia.console

import android.content.Context
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.auth.BockAuthInterceptor
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.ServerEndpointResolver
import com.bockmedia.console.data.repository.BockMediaRepository
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class BockMediaApp(context: Context) {
    val preferences = AppPreferences(context.applicationContext)

    private var cachedBaseUrl: String? = null
    private var cachedApi: BockMediaApi? = null
    private var cachedAdminUser: String? = null
    private var cachedAdminPass: String? = null
    private var cachedMobileToken: String? = null

    val repository: BockMediaRepository by lazy {
        BockMediaRepository(
            apiProvider = { api() },
            baseUrlProvider = { resolveBaseUrl() },
            preferences = preferences,
        )
    }

    suspend fun resolveBaseUrl(forceRefresh: Boolean = false): String {
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        return ServerEndpointResolver.resolve(
            preferences = preferences,
            authClient = buildHttpClient(user, pass, token),
            forceRefresh = forceRefresh,
        )
    }

    suspend fun api(): BockMediaApi {
        val base = resolveBaseUrl()
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        if (cachedApi != null && cachedBaseUrl == base &&
            cachedAdminUser == user && cachedAdminPass == pass && cachedMobileToken == token
        ) {
            return cachedApi!!
        }
        cachedBaseUrl = base
        cachedAdminUser = user
        cachedAdminPass = pass
        cachedMobileToken = token
        val client = buildHttpClient(user, pass, token)
        val contentType = "application/json".toMediaType()
        cachedApi = Retrofit.Builder()
            .baseUrl("$base/")
            .client(client)
            .addConverterFactory(bockJson.asConverterFactory(contentType))
            .build()
            .create(BockMediaApi::class.java)
        return cachedApi!!
    }

    fun invalidateApi() {
        cachedApi = null
        cachedBaseUrl = null
        ServerEndpointResolver.invalidate()
    }

    suspend fun buildAuthenticatedHttpClient(): OkHttpClient {
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        return buildHttpClient(user, pass, token)
    }

    private fun buildHttpClient(user: String?, pass: String?, token: String?): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(BockAuthInterceptor({ user }, { pass }, { token }))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }

    suspend fun hasServerUrl(): Boolean = preferences.hasAnyServerUrl()

    companion object {
        @Volatile
        private var instance: BockMediaApp? = null

        fun get(context: Context): BockMediaApp {
            return instance ?: synchronized(this) {
                instance ?: BockMediaApp(context.applicationContext).also { instance = it }
            }
        }

        /** Blocking API access for widget/worker (background thread only). */
        fun apiBlocking(context: Context): BockMediaApi = runBlocking {
            get(context).api()
        }

        fun activeBaseUrlBlocking(context: Context): String? = runBlocking {
            runCatching { get(context).resolveBaseUrl() }.getOrNull()
        }
    }
}
