package com.bockmedia.console.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.bockmedia.console.R
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.local.AppPreferences
import com.bockmedia.console.domain.model.computeNowPlayingProgress
import java.net.URL
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
            if (item.paused) {
                if (isNotEmpty()) append(" · ")
                append(context.getString(R.string.widget_paused))
            }
        }
        views.setTextViewText(R.id.widget_item_subtitle, subtitle.ifBlank { item.deviceName ?: "—" })

        val prog = computeNowPlayingProgress(item.timestamp, item.duration_ms, item.offset_ms, item.paused)
        views.setProgressBar(R.id.widget_item_progress, 1000, (prog.fraction * 1000).toInt(), false)

        loadArtwork(views, item.filepath)

        val enabled = NowPlayingSessionStore.canControl(item)
        views.setViewVisibility(R.id.widget_item_controls, if (enabled) View.VISIBLE else View.GONE)
        if (enabled) {
            val toggleAction = if (item.paused) NowPlayingWidget.ACTION_PLAY else NowPlayingWidget.ACTION_PAUSE
            val toggleIcon = if (item.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
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

    private fun loadArtwork(views: RemoteViews, filepath: String?) {
        val url = AppPreferences.artworkUrl(baseUrl.orEmpty(), filepath)
        if (url.isNullOrBlank()) {
            views.setImageViewResource(R.id.widget_item_art, R.mipmap.ic_launcher)
            return
        }
        try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val bmp = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            if (bmp != null) {
                views.setImageViewBitmap(R.id.widget_item_art, scaleArt(bmp))
                return
            }
        } catch (_: Exception) {
            // fall through
        }
        views.setImageViewResource(R.id.widget_item_art, R.mipmap.ic_launcher)
    }

    private fun scaleArt(src: Bitmap): Bitmap {
        val size = 128
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        return Bitmap.createScaledBitmap(
            src,
            max(1, (src.width * scale).toInt()),
            max(1, (src.height * scale).toInt()),
            true,
        )
    }
}
