package com.bockmedia.console.local

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest

/** Stable hardware id for this phone — survives app reinstall (not factory reset). */
object InstallIdentity {
    fun phoneId(context: Context): String {
        val raw = Settings.Secure.getString(
            context.applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.trim().orEmpty()
        if (raw.isBlank() || raw.equals("9774d56d682e549c", ignoreCase = true)) return ""
        return sha256(raw)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
    }
}
