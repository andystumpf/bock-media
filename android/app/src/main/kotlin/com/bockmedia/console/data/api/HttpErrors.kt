package com.bockmedia.console.data.api

import com.bockmedia.console.data.api.dto.OkResponse
import retrofit2.HttpException

fun httpErrorMessage(e: Throwable, fallback: String = "Request failed"): String {
    if (e is HttpException) {
        val raw = e.response()?.errorBody()?.string().orEmpty()
        if (raw.isNotBlank()) {
            runCatching { bockJson.decodeFromString<OkResponse>(raw) }.getOrNull()?.let { body ->
                friendlyApiError(body.error, body.code)?.let { return it }
            }
        }
        return when (e.code()) {
            401 -> "Authentication required — check server login in Settings"
            403 -> "Access denied"
            404 -> "Not found"
            else -> fallback
        }
    }
    return e.message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun friendlyApiError(error: String?, code: String?): String? {
    when (code?.takeIf { it.isNotBlank() } ?: error?.takeIf { it.isNotBlank() }) {
        "not_authenticated" -> return "Alexa not signed in — open Settings → Start browser login"
        "not_configured" -> return "Alexa remote isn't configured on the server"
        "password_required" -> return "Alexa login password required on the server"
        "device_not_found" -> return "Speaker not found — refresh devices in Settings"
        "not_installed" -> return "Alexa remote not installed on the server"
        "no_devices" -> return "No Alexa speakers found"
        "no_playlist" -> return "Playlist not found"
        "not_found" -> return "Not found"
    }
    return error?.takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
}
