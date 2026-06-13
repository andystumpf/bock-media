package com.bockmedia.console.ui.components

import android.content.Context
import com.bockmedia.console.domain.model.PlayTarget
import com.bockmedia.console.local.OfflineDownloadManager
import com.bockmedia.console.media.LocalPlaybackController

object PhonePlayback {
    fun canPlayLocally(target: PlayTarget, remoteOk: Boolean): Boolean =
        !remoteOk || OfflineDownloadManager.isDownloaded(target)

    suspend fun playLocally(
        context: Context,
        target: PlayTarget,
        shuffle: Boolean = false,
    ): String? {
        LocalPlaybackController.playTarget(context, target, shuffle)
        return LocalPlaybackController.state.value.error
    }

    fun playOrRemote(
        target: PlayTarget,
        remoteOk: Boolean,
        onPlayRemote: (PlayTarget) -> Unit,
        onPlayLocal: () -> Unit,
    ) {
        if (remoteOk) onPlayRemote(target) else onPlayLocal()
    }
}
