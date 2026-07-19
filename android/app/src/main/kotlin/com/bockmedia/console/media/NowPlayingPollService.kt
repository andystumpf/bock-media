package com.bockmedia.console.media

import com.bockmedia.console.data.api.dto.AlexaDevice
import com.bockmedia.console.data.api.dto.NowPlayingDeviceItem
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.alexaControlsAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single poller for `/api/nowplaying_devices` — 5s playing / 20s idle. */
object NowPlayingPollService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var subscribers = 0
    private var repository: BockMediaRepository? = null
    private var polling = false

    private val _items = MutableStateFlow<List<NowPlayingDeviceItem>>(emptyList())
    val items: StateFlow<List<NowPlayingDeviceItem>> = _items.asStateFlow()

    private val _controlsAvailable = MutableStateFlow(false)
    val controlsAvailable: StateFlow<Boolean> = _controlsAvailable.asStateFlow()

    private val _alexaDevices = MutableStateFlow<List<AlexaDevice>>(emptyList())
    val alexaDevices: StateFlow<List<AlexaDevice>> = _alexaDevices.asStateFlow()

    fun configure(repo: BockMediaRepository) {
        repository = repo
    }

    fun addSubscriber() {
        subscribers++
        startIfNeeded()
    }

    fun removeSubscriber() {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        if (subscribers == 0) polling = false
    }

    fun refreshNow() {
        val repo = repository ?: return
        scope.launch { tick(repo) }
    }

    private fun startIfNeeded() {
        if (polling || subscribers <= 0 || repository == null) return
        polling = true
        scope.launch {
            val repo = repository ?: return@launch
            delay(2_500)
            while (isActive && subscribers > 0) {
                tick(repo)
                val playing = _items.value.any { !it.paused && !it.track.isNullOrBlank() }
                    || LocalPlaybackController.state.value.isPlaying
                delay(if (playing) 5_000L else 20_000L)
            }
            polling = false
        }
    }

    private suspend fun tick(repo: BockMediaRepository) {
        mutex.withLock {
            runCatching {
                val np = repo.nowPlayingDevices()
                _controlsAvailable.value = np.controlsAvailable
                var alexa = _alexaDevices.value
                if (np.controlsAvailable && alexa.isEmpty()) {
                    runCatching {
                        alexa = repo.alexaRemoteDevices().devices
                        _alexaDevices.value = alexa
                    }
                }
                _items.value = np.items.filter { !it.deviceId.startsWith("client-") }
            }
        }
    }
}
