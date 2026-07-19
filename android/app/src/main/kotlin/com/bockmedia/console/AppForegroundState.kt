package com.bockmedia.console

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.bockmedia.console.media.LocalPlaybackService

/** Process-wide foreground flag for background playback policy (crossfade, FGS). */
object AppForegroundState {
    @Volatile
    var isInForeground: Boolean = false
        private set

    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        isInForeground = true
                        LocalPlaybackService.onAppForeground()
                    }
                    Lifecycle.Event.ON_STOP -> isInForeground = false
                    else -> {}
                }
            },
        )
    }
}
