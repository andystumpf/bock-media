package com.bockmedia.console.ui.components

import com.bockmedia.console.data.api.dto.AlexaRemoteStatus
import com.bockmedia.console.data.repository.BockMediaRepository
import com.bockmedia.console.ui.alexaControlsAvailable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * In-memory cache of Alexa status + playable device options.
 *
 * The device picker used to make three sequential network calls on every open
 * (a probe=true status check that invalidated server-side auth, then devices,
 * then groups). That made opening anything from home feel very slow. We cache a
 * snapshot so the sheet paints instantly and refreshes in the background.
 */
object DeviceCatalog {
    data class Snapshot(
        val status: AlexaRemoteStatus?,
        val remoteReady: Boolean,
        val options: List<DeviceOption>,
        val atMs: Long,
    )

    private const val TTL_MS = 60_000L

    @Volatile
    private var cached: Snapshot? = null

    fun peek(): Snapshot? = cached

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean =
        cached?.let { now - it.atMs < TTL_MS } == true

    fun invalidate() {
        cached = null
    }

    suspend fun refresh(repository: BockMediaRepository, probe: Boolean): Snapshot = coroutineScope {
        val status = runCatching { repository.alexaRemoteStatus(probe = probe) }.getOrNull()
        val remoteReady = alexaControlsAvailable(status)
        val options = if (remoteReady) {
            runCatching {
                val devicesDef = async { repository.alexaRemoteDevices().devices }
                val groupsDef = async { repository.deviceGroups().items }
                buildDeviceOptions(groupsDef.await(), devicesDef.await())
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        Snapshot(status, remoteReady, options, System.currentTimeMillis()).also { cached = it }
    }
}
