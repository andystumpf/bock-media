package com.bockmedia.console.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object PlaybackArtwork {
    private val cache = ConcurrentHashMap<String, String>()

    fun embeddedArtUri(context: Context, localFile: File): String? {
        if (!localFile.exists() || localFile.length() <= 0) return null
        val key = "${localFile.absolutePath}|${localFile.lastModified()}"
        cache[key]?.let { cached ->
            val path = cached.removePrefix("file://")
            if (File(path).exists()) return cached
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(localFile.absolutePath)
            val bytes = retriever.embeddedPicture ?: return null
            val out = File(context.cacheDir, "playback-art/${localFile.name.hashCode()}.jpg")
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            Uri.fromFile(out).toString().also { cache[key] = it }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
