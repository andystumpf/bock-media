package com.bockmedia.console

import android.content.Context
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.auth.BockAuthInterceptor
import com.bockmedia.console.data.local.ApiResponseCache
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.data.network.NetworkHints
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
    val responseCache = ApiResponseCache(context.applicationContext)
    private val appContext = context.applicationContext

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
            cache = responseCache,
        )
    }

    suspend fun resolveBaseUrl(forceRefresh: Boolean = false): String {
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        return ServerEndpointResolver.resolve(
            preferences = preferences,
            authProbeClient = buildHttpClient(user, pass, token, local, external),
            preferExternal = !NetworkHints.onWifi(appContext),
            forceRefresh = forceRefresh,
        )
    }

    suspend fun api(): BockMediaApi {
        val base = resolveBaseUrl()
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        if (cachedApi != null && cachedBaseUrl == base &&
            cachedAdminUser == user && cachedAdminPass == pass && cachedMobileToken == token
        ) {
            return cachedApi!!
        }
        cachedBaseUrl = base
        cachedAdminUser = user
        cachedAdminPass = pass
        cachedMobileToken = token
        val client = buildHttpClient(user, pass, token, local, external)
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
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        return buildHttpClient(user, pass, token, local, external)
    }

    private fun buildPlainHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }

    private fun buildHttpClient(
        user: String?,
        pass: String?,
        token: String?,
        localUrl: String?,
        externalUrl: String?,
    ): OkHttpClient {
        val localHosts = AppPreferences.localHosts(localUrl, externalUrl)
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(BockAuthInterceptor({ localHosts }, { user }, { pass }, { token }))
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
