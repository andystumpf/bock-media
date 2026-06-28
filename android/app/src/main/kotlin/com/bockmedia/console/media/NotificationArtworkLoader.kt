package com.bockmedia.console.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/** Async artwork for widget/notification — never block the main thread on HTTP. */
object NotificationArtworkLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = LruCache<String, Bitmap>(12)
    private val inflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = cache.get(url)

    fun load(url: String, onReady: () -> Unit) {
        if (cache.get(url) != null) {
            onReady()
            return
        }
        if (!inflight.add(url)) return
        scope.launch {
            val bitmap = runCatching { download(url) }.getOrNull()
            inflight.remove(url)
            if (bitmap != null) {
                cache.put(url, bitmap)
            }
            mainHandler.post { onReady() }
        }
    }

    private fun download(url: String): Bitmap? {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.getInputStream().use { stream ->
            val bmp = BitmapFactory.decodeStream(stream) ?: return null
            val size = 256
            val scale = min(size.toFloat() / bmp.width, size.toFloat() / bmp.height)
            return Bitmap.createScaledBitmap(
                bmp,
                max(1, (bmp.width * scale).toInt()),
                max(1, (bmp.height * scale).toInt()),
                true,
            )
        }
    }
}
