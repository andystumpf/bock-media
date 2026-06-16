package com.bockmedia.console.ui.components

import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bockmedia.console.BockMediaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Single authenticated Coil instance with a generous disk cache.
 *
 * Must be installed from [com.bockmedia.console.MainActivity] after
 * [BockMediaApp.get] — never from inside [BockMediaApp]'s constructor (that
 * re-enters [BockMediaApp.get] and overflows the stack).
 */
object BockImageLoader {
    @Volatile
    private var installed = false

    fun install(context: Context, app: BockMediaApp) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val client: OkHttpClient = runBlocking(Dispatchers.IO) {
                runCatching { app.buildAuthenticatedHttpClient() }.getOrElse {
                    OkHttpClient.Builder().build()
                }
            }
            Coil.setImageLoader(
                ImageLoader.Builder(appContext)
                    .okHttpClient(client)
                    .memoryCache {
                        MemoryCache.Builder(appContext)
                            .maxSizePercent(0.25)
                            .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(appContext.cacheDir.resolve("image_cache"))
                            .maxSizeBytes(256L * 1024 * 1024)
                            .build()
                    }
                    .crossfade(false)
                    .build(),
            )
            installed = true
        }
    }

    /** Call when credentials change so the next install picks up a new OkHttp client. */
    fun reset() {
        synchronized(this) { installed = false }
    }
}
