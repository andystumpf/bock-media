package com.bockmedia.console.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** Encrypted storage for admin password and mobile API token. */
class SecureCredentialStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        createEncrypted(app)
            ?: run {
                app.deleteSharedPreferences("bockmedia_secure")
                createEncrypted(app)
            }
            ?: run {
                android.util.Log.w(
                    "SecureCredentialStore",
                    "Encrypted prefs unavailable after retry; falling back to plaintext storage",
                )
                app.getSharedPreferences("bockmedia_secure_fallback", Context.MODE_PRIVATE)
            }
    }

    private fun createEncrypted(app: Context): SharedPreferences? = try {
        EncryptedSharedPreferences.create(
            "bockmedia_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            app,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        android.util.Log.w("SecureCredentialStore", "EncryptedSharedPreferences init failed", e)
        null
    }

    fun getAdminPass(): String? = runCatching {
        prefs.getString(KEY_ADMIN_PASS, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun setAdminPass(value: String?) {
        runCatching {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ADMIN_PASS) else putString(KEY_ADMIN_PASS, value)
            }.apply()
        }.onFailure {
            android.util.Log.e("SecureCredentialStore", "setAdminPass failed", it)
        }
    }

    fun getMobileToken(): String? = runCatching {
        prefs.getString(KEY_MOBILE_TOKEN, null)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun setMobileToken(value: String?) {
        runCatching {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_MOBILE_TOKEN) else putString(KEY_MOBILE_TOKEN, value)
            }.apply()
        }.onFailure {
            android.util.Log.e("SecureCredentialStore", "setMobileToken failed", it)
        }
    }

    companion object {
        private const val KEY_ADMIN_PASS = "admin_pass"
        private const val KEY_MOBILE_TOKEN = "mobile_token"
    }
}
