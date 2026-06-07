package com.bockmedia.console

import android.content.Context
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.auth.AuthInterceptor
import com.bockmedia.console.data.auth.MobileTokenInterceptor
import com.bockmedia.console.data.local.AppPreferences
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
        BockMediaRepository(apiProvider = { api() }, preferences = preferences)
    }

    suspend fun api(): BockMediaApi {
        val base = preferences.getServerUrlSync()
            ?: throw IllegalStateException("Server URL not configured")
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
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(MobileTokenInterceptor { token })
            .addInterceptor(AuthInterceptor({ user }, { pass }))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
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
    }

    suspend fun hasServerUrl(): Boolean = !preferences.getServerUrlSync().isNullOrBlank()

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
    }
}
