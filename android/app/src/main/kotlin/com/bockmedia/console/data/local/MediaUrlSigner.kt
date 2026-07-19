package com.bockmedia.console.data.local

import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC signing for /stream/ and /artwork/ URLs (mirrors server.py _append_media_sig). */
object MediaUrlSigner {
    private const val TTL_SEC = 86_400L

    fun appendMediaSig(path: String, query: Map<String, String>, secret: String): String {
        val trimmedSecret = secret.trim()
        if (trimmedSecret.isEmpty()) {
            return appendQuery(path, query)
        }
        val expires = ((System.currentTimeMillis() / 1000L) + TTL_SEC).toString()
        val pairs = query.filterKeys { it !in setOf("sig", "expires") }.toList().sortedBy { it.first }
        // Flask verifies against request.path (URL-decoded), not the wire-encoded path.
        val signPath = URLDecoder.decode(path, Charsets.UTF_8.name())
        val canonical = buildString {
            append(signPath)
            append('\n')
            append(expires)
            for ((k, v) in pairs) {
                append('\n')
                append(k)
                append('=')
                append(v)
            }
        }
        val sig = hmacSha256(trimmedSecret, canonical)
        val signed = query.toMutableMap()
        signed["expires"] = expires
        signed["sig"] = sig
        return appendQuery(path, signed)
    }

    private fun appendQuery(path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return path
        val qs = query.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8").replace("+", "%20")}=" +
                java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }
        return "$path?$qs"
    }

    private fun hmacSha256(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
