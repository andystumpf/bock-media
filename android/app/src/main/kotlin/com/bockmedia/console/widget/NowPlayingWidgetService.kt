package com.bockmedia.console.widget

import android.content.Intent
import android.widget.RemoteViewsService

class NowPlayingWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NowPlayingWidgetFactory(applicationContext)
}
