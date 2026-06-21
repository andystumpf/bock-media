package com.bockmedia.console.media.auto

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.bockmedia.console.media.LocalPlaybackService

/**
 * Android Auto / Automotive browse root. Playback delegates to [LocalPlaybackService].
 */
class BockMediaBrowserService : MediaBrowserServiceCompat() {

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, TAG).apply {
            isActive = true
        }
        sessionToken = mediaSession?.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        val items = when (parentId) {
            ROOT_ID -> mutableListOf(
                browseItem("continue", "Continue listening"),
                browseItem("playlists", "Playlists"),
            )
            else -> mutableListOf()
        }
        result.sendResult(items)
    }

    private fun browseItem(id: String, title: String): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BockMediaBrowser"
        private const val ROOT_ID = "bock_root"
    }
}
