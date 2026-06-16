package com.bockmedia.console.domain.model

import java.net.URI
import java.net.URLDecoder

/** Host-independent media paths for artwork (survives LAN ↔ external IP switches). */
object ArtworkPaths {
    fun extractMediaPath(urlOrPath: String?): String? {
        val raw = urlOrPath?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (!raw.contains("://")) return raw.trimStart('/')
        return runCatching {
            val path = URI(raw).rawPath.orEmpty()
            when {
                path.startsWith("/artwork/") ->
                    URLDecoder.decode(path.removePrefix("/artwork/"), Charsets.UTF_8.name())
                path.startsWith("/stream/") ->
                    URLDecoder.decode(path.removePrefix("/stream/"), Charsets.UTF_8.name())
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
