package com.bockmedia.console.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.bockmedia.console.BockMediaApp
import com.bockmedia.console.R
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import kotlin.math.max
import kotlin.math.min

class NowPlayingWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var items: List<NowPlayingDeviceItem> = emptyList()
    private var controlsAvailable = false
    private var baseUrl: String? = null

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val snap = NowPlayingSessionStore.snapshot
        items = snap?.items.orEmpty()
        controlsAvailable = snap?.controlsAvailable == true
        baseUrl = snap?.baseUrl
        items.forEach { item -> artworkUrlFor(item)?.let { prefetchArtwork(it) } }
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items.getOrNull(position) ?: return emptyRow()
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing_item)
        views.setTextViewText(R.id.widget_item_track, item.track ?: context.getString(R.string.widget_unknown_track))
        val subtitle = buildString {
            item.artist?.let { append(it) }
            item.deviceName?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
            if (item.paused || item.stopped) {
                if (isNotEmpty()) append(" · ")
                append(context.getString(if (item.paused) R.string.widget_paused else R.string.widget_stopped))
            }
        }
        views.setTextViewText(R.id.widget_item_subtitle, subtitle.ifBlank { item.deviceName ?: "—" })

        val prog = computeNowPlayingProgress(item.timestamp, item.duration_ms, item.offset_ms, item.paused || item.stopped)
        views.setProgressBar(R.id.widget_item_progress, 1000, (prog.fraction * 1000).toInt(), false)

        loadArtwork(views, item)

        val enabled = NowPlayingSessionStore.canControl(item)
        views.setViewVisibility(R.id.widget_item_controls, if (enabled) View.VISIBLE else View.GONE)
        if (enabled) {
            val toggleAction = if (item.paused || item.stopped) NowPlayingWidget.ACTION_PLAY else NowPlayingWidget.ACTION_PAUSE
            val toggleIcon = if (item.paused || item.stopped) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            views.setImageViewResource(R.id.widget_item_btn_pause, toggleIcon)
            views.setOnClickFillInIntent(
                R.id.widget_item_btn_pause,
                Intent().apply {
                    action = toggleAction
                    putExtra(NowPlayingWidget.EXTRA_DEVICE_ID, item.deviceId)
                    putExtra(NowPlayingWidget.EXTRA_DEVICE_NAME, item.deviceName)
                },
            )
            views.setOnClickFillInIntent(
                R.id.widget_item_btn_next,
                Intent().apply {
                    action = NowPlayingWidget.ACTION_NEXT
                    putExtra(NowPlayingWidget.EXTRA_DEVICE_ID, item.deviceId)
                    putExtra(NowPlayingWidget.EXTRA_DEVICE_NAME, item.deviceName)
                },
            )
        }

        views.setOnClickFillInIntent(
            R.id.widget_item_root,
            Intent().apply { action = NowPlayingWidget.ACTION_OPEN },
        )
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.deviceId?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun emptyRow(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing_item)
        views.setTextViewText(R.id.widget_item_track, context.getString(R.string.widget_nothing_playing))
        views.setViewVisibility(R.id.widget_item_controls, View.GONE)
        return views
    }

    private fun artworkUrlFor(item: NowPlayingDeviceItem): String? =
        item.artworkUrl ?: baseUrl?.let { AppPreferences.artworkUrl(it, item.filepath) }

    private fun loadArtwork(views: RemoteViews, item: NowPlayingDeviceItem) {
        val bmp = artworkUrlFor(item)?.let { artCache.get(it) }
        if (bmp != null) {
            views.setImageViewBitmap(R.id.widget_item_art, bmp)
        } else {
            views.setImageViewResource(R.id.widget_item_art, R.mipmap.ic_launcher)
        }
    }

    private fun prefetchArtwork(url: String) {
        if (artCache.get(url) != null) return
        val bmp = try {
            runBlocking {
                val client = BockMediaApp.get(context.applicationContext).buildAuthenticatedHttpClient()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@runBlocking null
                    resp.body?.byteStream()?.use { stream ->
                        val raw = BitmapFactory.decodeStream(stream) ?: return@runBlocking null
                        val size = 128
                        val scale = min(size.toFloat() / raw.width, size.toFloat() / raw.height)
                        Bitmap.createScaledBitmap(
                            raw,
                            max(1, (raw.width * scale).toInt()),
                            max(1, (raw.height * scale).toInt()),
                            true,
                        )
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
        if (bmp != null) artCache.put(url, bmp)
    }

    companion object {
        private val artCache = LruCache<String, Bitmap>(12)
    }
}
