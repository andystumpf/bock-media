package com.bockmedia.console.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bockmedia")

class AppPreferences(private val context: Context) {
    private val keyServerUrl = stringPreferencesKey("server_url")
    private val keyAdminUser = stringPreferencesKey("admin_user")
    private val keyAdminPass = stringPreferencesKey("admin_pass")
    private val keyMobileToken = stringPreferencesKey("mobile_token")

    val serverUrl: Flow<String?> = context.dataStore.data.map { it[keyServerUrl] }
    val adminUser: Flow<String?> = context.dataStore.data.map { it[keyAdminUser] }
    val adminPass: Flow<String?> = context.dataStore.data.map { it[keyAdminPass] }
    val mobileToken: Flow<String?> = context.dataStore.data.map { it[keyMobileToken] }

    suspend fun getServerUrlSync(): String? = serverUrl.first()?.takeIf { it.isNotBlank() }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[keyServerUrl] = normalizeUrl(url) }
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

    companion object {
        fun normalizeUrl(raw: String): String {
            var url = raw.trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }

        fun isValidUrl(raw: String): Boolean {
            return try {
                val uri = android.net.Uri.parse(normalizeUrl(raw))
                !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        fun artworkUrl(base: String, filepath: String?): String? {
            if (filepath.isNullOrBlank()) return null
            val rel = filepath.trimStart('/')
            val encoded = rel.split('/').joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            return "${normalizeUrl(base)}/artwork/$encoded"
        }
    }
}
