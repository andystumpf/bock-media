package com.bockmedia.console.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bockmedia")

class AppPreferences(private val context: Context) {
    private val keySearchAllLibraries = booleanPreferencesKey("search_all_libraries")
    private val keySearchSourcePath = stringPreferencesKey("search_source_path")

    val searchAllLibraries: Flow<Boolean> = context.dataStore.data.map { it[keySearchAllLibraries] != false }
    val searchSourcePath: Flow<String?> = context.dataStore.data.map { it[keySearchSourcePath] }

    suspend fun isSearchAllLibrariesSync(): Boolean = searchAllLibraries.first()

    suspend fun getSearchSourcePathSync(): String? = searchSourcePath.first()?.takeIf { it.isNotBlank() }

    suspend fun setSearchAllLibraries(all: Boolean) {
        context.dataStore.edit { prefs ->
            if (all) prefs.remove(keySearchAllLibraries) else prefs[keySearchAllLibraries] = false
        }
    }

    suspend fun setSearchSourcePath(path: String?) {
        context.dataStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(keySearchSourcePath) else prefs[keySearchSourcePath] = path
        }
    }

    private val keyLocalUrl = stringPreferencesKey("local_server_url")
    private val keyExternalUrl = stringPreferencesKey("external_server_url")
    /** Legacy single-URL key — migrated to external on read. */
    private val keyServerUrl = stringPreferencesKey("server_url")
    private val keyAdminUser = stringPreferencesKey("admin_user")
    private val keyAdminPass = stringPreferencesKey("admin_pass")
    private val keyMobileToken = stringPreferencesKey("mobile_token")
    private val keyRememberMe = booleanPreferencesKey("remember_me")
    private val keyHasConnected = booleanPreferencesKey("has_connected")
    private val keyDownloadWifiOnly = booleanPreferencesKey("download_wifi_only")
    private val keyCrossfadeSeconds = intPreferencesKey("crossfade_seconds")
    private val keyContinueAfterQueue = stringPreferencesKey("continue_after_queue")
    private val keyLastEndpoint = stringPreferencesKey("last_good_endpoint")

    val rememberMe: Flow<Boolean> = context.dataStore.data.map { it[keyRememberMe] != false }
    val downloadWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[keyDownloadWifiOnly] == true }
    val crossfadeSeconds: Flow<Int> = context.dataStore.data.map { (it[keyCrossfadeSeconds] ?: 0).coerceIn(0, 20) }

    val localServerUrl: Flow<String?> = context.dataStore.data.map { it[keyLocalUrl] }
    val externalServerUrl: Flow<String?> = context.dataStore.data.map { it[keyExternalUrl] }

    val adminUser: Flow<String?> = context.dataStore.data.map { it[keyAdminUser] }
    val adminPass: Flow<String?> = context.dataStore.data.map { it[keyAdminPass] }
    val mobileToken: Flow<String?> = context.dataStore.data.map { it[keyMobileToken] }

    suspend fun getLocalServerUrlSync(): String? = localServerUrl.first()?.takeIf { it.isNotBlank() }

    suspend fun getExternalServerUrlSync(): String? {
        externalServerUrl.first()?.takeIf { it.isNotBlank() }?.let { return it }
        return legacyServerUrl()
    }

    /** @deprecated Use resolver; kept for migration. */
    suspend fun getServerUrlSync(): String? =
        getLocalServerUrlSync() ?: getExternalServerUrlSync()

    suspend fun setServerUrls(local: String?, external: String?) {
        context.dataStore.edit { prefs ->
            setUrl(prefs, keyLocalUrl, local)
            setUrl(prefs, keyExternalUrl, external)
            prefs.remove(keyServerUrl)
        }
    }

    suspend fun clearServerUrls() {
        context.dataStore.edit {
            it.remove(keyLocalUrl)
            it.remove(keyExternalUrl)
            it.remove(keyServerUrl)
        }
    }

    suspend fun setAdminCredentials(user: String?, pass: String?) {
        context.dataStore.edit {
            if (user.isNullOrBlank()) it.remove(keyAdminUser) else it[keyAdminUser] = user
            if (pass.isNullOrBlank()) it.remove(keyAdminPass) else it[keyAdminPass] = pass
        }
    }

    suspend fun setMobileToken(token: String?) {
        context.dataStore.edit {
            if (token.isNullOrBlank()) it.remove(keyMobileToken) else it[keyMobileToken] = token
        }
    }

    suspend fun isRememberMeSync(): Boolean =
        context.dataStore.data.first()[keyRememberMe] != false

    /** Blocking read for OkHttp interceptors (background threads only). */
    fun adminUserNow(): String? = runBlocking { adminUser.first()?.trim()?.takeIf { it.isNotEmpty() } }

    fun adminPassNow(): String? = runBlocking { adminPass.first()?.trim()?.takeIf { it.isNotEmpty() } }

    fun mobileTokenNow(): String? = runBlocking { mobileToken.first()?.trim()?.takeIf { it.isNotEmpty() } }

    fun localServerUrlNow(): String? = runBlocking { getLocalServerUrlSync() }

    fun externalServerUrlNow(): String? = runBlocking { getExternalServerUrlSync() }

    suspend fun isDownloadWifiOnlySync(): Boolean = downloadWifiOnly.first()

    suspend fun getCrossfadeSecondsSync(): Int = crossfadeSeconds.first()

    suspend fun getContinueAfterQueueSync(): String =
        context.dataStore.data.first()[keyContinueAfterQueue]?.takeIf { it.isNotBlank() } ?: "off"

    suspend fun setContinueAfterQueue(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[keyContinueAfterQueue] = mode
        }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[keyCrossfadeSeconds] = seconds.coerceIn(0, 20)
        }
    }

    suspend fun setDownloadWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { prefs ->
            if (wifiOnly) prefs[keyDownloadWifiOnly] = true else prefs.remove(keyDownloadWifiOnly)
        }
    }

    suspend fun setRememberMe(remember: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[keyRememberMe] = remember
        }
    }

    /** Last endpoint that answered a health probe — used to skip slow startup probing. */
    suspend fun getLastGoodEndpointSync(): String? =
        context.dataStore.data.first()[keyLastEndpoint]?.takeIf { it.isNotBlank() }

    suspend fun setLastGoodEndpoint(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(keyLastEndpoint) else prefs[keyLastEndpoint] = url
        }
    }

    suspend fun hasConnectedBefore(): Boolean =
        context.dataStore.data.first()[keyHasConnected] == true

    suspend fun setHasConnected(connected: Boolean) {
        context.dataStore.edit { prefs ->
            if (connected) prefs[keyHasConnected] = true else prefs.remove(keyHasConnected)
        }
    }

    suspend fun clearCredentialsIfNotRemembered() {
        if (!isRememberMeSync()) {
            setAdminCredentials(null, null)
            setMobileToken(null)
        }
    }

    /** Always use build-time LAN / external URLs — not user-editable. */
    suspend fun applyBuildServerUrls() {
        setServerUrls(
            local = com.bockmedia.console.BuildConfig.DEFAULT_LOCAL_SERVER_URL.takeIf { it.isNotBlank() },
            external = com.bockmedia.console.BuildConfig.DEFAULT_EXTERNAL_SERVER_URL.takeIf { it.isNotBlank() },
        )
    }

    suspend fun applyBuildDefaultsIfEmpty() {
        applyBuildServerUrls()
        if (adminUser.first().isNullOrBlank()) {
            setAdminCredentials(
                com.bockmedia.console.BuildConfig.DEFAULT_ADMIN_USER.takeIf { it.isNotBlank() },
                com.bockmedia.console.BuildConfig.DEFAULT_ADMIN_PASSWORD.takeIf { it.isNotBlank() },
            )
        }
        if (mobileToken.first().isNullOrBlank()) {
            setMobileToken(com.bockmedia.console.BuildConfig.DEFAULT_MOBILE_API_TOKEN.takeIf { it.isNotBlank() })
        }
    }

    suspend fun hasAnyServerUrl(): Boolean =
        !getLocalServerUrlSync().isNullOrBlank() || !getExternalServerUrlSync().isNullOrBlank()

    private suspend fun legacyServerUrl(): String? =
        context.dataStore.data.first()[keyServerUrl]?.takeIf { it.isNotBlank() }

    private fun setUrl(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, raw: String?) {
        if (raw.isNullOrBlank()) prefs.remove(key) else prefs[key] = normalizeUrl(raw)
    }

    companion object {
        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        fun isValidUrl(raw: String): Boolean {
            if (raw.isBlank()) return false
            return try {
                val uri = java.net.URI(normalizeUrl(raw))
                !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        fun encodeMediaPath(filepath: String): String {
            val rel = filepath.trimStart('/')
            return rel.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        }

        fun artworkUrl(base: String, filepath: String?, sizePx: Int? = null): String? {
            if (filepath.isNullOrBlank()) return null
            val url = "${normalizeUrl(base)}/artwork/${encodeMediaPath(filepath)}"
            return if (sizePx != null && sizePx > 0) "$url?size=$sizePx" else url
        }

        const val CELLULAR_STREAM_BITRATE_KBPS = 128

        fun streamUrl(
            base: String,
            filepath: String?,
            title: String? = null,
            artist: String? = null,
            lowBandwidth: Boolean = false,
        ): String? {
            if (filepath.isNullOrBlank()) return null
            val params = mutableListOf<String>()
            title?.trim()?.takeIf { it.isNotEmpty() }?.let {
                params += "title=${java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")}"
            }
            artist?.trim()?.takeIf { it.isNotEmpty() }?.let {
                params += "artist=${java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")}"
            }
            if (lowBandwidth) params += "br=$CELLULAR_STREAM_BITRATE_KBPS"
            val qs = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
            return "${normalizeUrl(base)}/stream/${encodeMediaPath(filepath)}$qs"
        }

        fun hostOf(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return try {
                java.net.URI(normalizeUrl(raw)).host?.lowercase()
            } catch (_: Exception) {
                null
            }
        }

        /** True when the host is only reachable on the home LAN (not over cellular). */
        fun isLanHost(url: String?, localUrl: String? = null, externalUrl: String? = null): Boolean {
            val host = hostOf(url) ?: return false
            if (host in localHosts(localUrl, externalUrl)) return true
            if (host == "localhost" || host == "127.0.0.1") return true
            if (host.startsWith("192.168.") || host.startsWith("10.")) return true
            return host.endsWith(".local")
        }

        fun localHosts(localUrl: String?, externalUrl: String?): Set<String> {
            return buildSet {
                hostOf(localUrl)?.let { add(it) }
                add("localhost")
                add("127.0.0.1")
                add("10.0.2.2") // Android emulator → host machine
            }
        }
    }
}
