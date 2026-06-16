package com.bockmedia.console.domain.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Prevents stacked home feed / artwork warm jobs from wedging the server or UI. */
object HomeLoadCoordinator {
    private val loadMutex = Mutex()
    private const val MIN_RELOAD_MS = 45_000L

    @Volatile
    var lastSuccessfulLoadMs: Long = 0L
        private set

    fun markLoaded() {
        lastSuccessfulLoadMs = System.currentTimeMillis()
    }

    fun shouldSkipReload(): Boolean =
        HomeFeedCache.getIfFresh() != null &&
            System.currentTimeMillis() - lastSuccessfulLoadMs < MIN_RELOAD_MS

    suspend fun <T> withLoadLock(block: suspend () -> T): T = loadMutex.withLock { block() }
}
