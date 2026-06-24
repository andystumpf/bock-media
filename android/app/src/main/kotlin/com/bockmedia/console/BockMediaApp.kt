package com.bockmedia.console

import android.content.Context
import com.bockmedia.console.data.api.BockMediaApi
import com.bockmedia.console.data.api.bockJson
import com.bockmedia.console.data.auth.BockAuthInterceptor
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.BuildConfig
import com.bockmedia.console.data.network.NetworkReachability
import com.bockmedia.console.data.network.ServerEndpointResolver
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.local.ClientIdStore
import com.bockmedia.console.domain.model.AutomationSessionCache
import com.bockmedia.console.domain.model.HomeArtworkCache
import com.bockmedia.console.domain.model.HomeCachePersistence
import com.bockmedia.console.domain.model.HomeFeedCache
import com.bockmedia.console.domain.model.LibraryCachePersistence
import com.bockmedia.console.domain.model.LibrarySessionCache
import com.bockmedia.console.domain.model.SearchBrowseSessionCache
import com.bockmedia.console.domain.model.HomeTileEngagement
import com.bockmedia.console.ui.components.BockImageLoader
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class BockMediaApp(private val appContext: Context) {
    val preferences = AppPreferences(appContext.applicationContext)

    init {
        HomeTileEngagement.init(appContext.applicationContext)
    }

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
            clientIdProvider = { ClientIdStore.clientId(appContext) },
        )
    }

    @Volatile private var endpointPrimed = false

    suspend fun resolveBaseUrl(forceRefresh: Boolean = false): String {
        NetworkReachability.update(appContext)
        if (!endpointPrimed) {
            endpointPrimed = true
            val seed = configuredEndpointUrl()
            ServerEndpointResolver.prime(seed)
            seed?.let { repository.primeBaseUrl(it) }
        }
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        return ServerEndpointResolver.resolve(
            preferences = preferences,
            probeClient = buildHttpClient(user, pass, token, local, external),
            forceRefresh = forceRefresh,
            wifiAvailable = NetworkReachability.onWifi,
        ).also { repository.primeBaseUrl(it) }
    }

    /** Endpoint for instant paint — skips probes; cellular uses external, not LAN. */
    suspend fun configuredEndpointUrl(): String? {
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        val lastGood = preferences.getLastGoodEndpointSync()
        if (!NetworkReachability.onWifi) {
            return when {
                !external.isNullOrBlank() -> external
                lastGood != null && !AppPreferences.isLanHost(lastGood, local, external) -> lastGood
                else -> BuildConfig.DEFAULT_EXTERNAL_SERVER_URL.takeIf { it.isNotBlank() }
            }
        }
        return lastGood ?: local ?: external
    }

    /** Switch to external URL when leaving Wi‑Fi — keeps feed/art caches, drops stale LAN client. */
    fun onCellularNetwork() {
        invalidateEndpoint()
        endpointPrimed = false
        runBlocking(Dispatchers.IO) {
            configuredEndpointUrl()?.let { url ->
                ServerEndpointResolver.prime(url)
                repository.primeBaseUrl(url)
                endpointPrimed = true
            }
        }
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

    /**
     * Full reset — use when credentials or server URLs change (Setup). Wipes the
     * API client, endpoint cache, and all content/artwork caches.
     */
    fun invalidateApi() {
        invalidateEndpoint()
        repository.clearCaches()
        HomeFeedCache.invalidate()
        HomeArtworkCache.invalidate()
        HomeCachePersistence.clear(appContext)
        LibrarySessionCache.invalidate()
        LibraryCachePersistence.clear(appContext)
        AutomationSessionCache.invalidate()
        SearchBrowseSessionCache.invalidate()
        BockImageLoader.reset()
    }

    /**
     * Lightweight reset — drops the API client and forces endpoint re-resolution
     * but KEEPS the home feed and artwork caches so transient reconnects still
     * render instantly from cache.
     */
    fun invalidateEndpoint() {
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

    /** Longer read timeout for ExoPlayer streaming — default 30s gaps cause false skips. */
    suspend fun buildPlaybackHttpClient(): OkHttpClient {
        val user = preferences.adminUser.first()
        val pass = preferences.adminPass.first()
        val token = preferences.mobileToken.first()
        val local = preferences.getLocalServerUrlSync()
        val external = preferences.getExternalServerUrlSync()
        return buildHttpClient(user, pass, token, local, external, readTimeoutSec = 120)
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
        readTimeoutSec: Long = 30,
    ): OkHttpClient {
        val localHosts = AppPreferences.localHosts(localUrl, externalUrl)
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(BockAuthInterceptor({ localHosts }, { user }, { pass }, { token }))
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
