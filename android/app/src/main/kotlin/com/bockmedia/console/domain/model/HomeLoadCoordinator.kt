package com.bockmedia.console.domain.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Debounce ON_RESUME prefs pull after cold-boot pull (ms). */
const val RESUME_PULL_DEBOUNCE_MS = 5_000L

/** True when a resume-triggered prefs pull should be skipped (duplicate of boot pull). */
fun shouldSkipResumePull(
    lastPullCompletedMs: Long,
    nowMs: Long,
    debounceMs: Long = RESUME_PULL_DEBOUNCE_MS,
): Boolean = lastPullCompletedMs > 0L && nowMs - lastPullCompletedMs < debounceMs

/** Prevents stacked home feed / artwork warm jobs from wedging the server or UI. */
object HomeLoadCoordinator {
    private val loadMutex = Mutex()
    private const val MIN_RELOAD_MS = 600_000L // 10 min between full reloads

    @Volatile
    var lastSuccessfulLoadMs: Long = 0L
        private set

    fun markLoaded() {
        lastSuccessfulLoadMs = System.currentTimeMillis()
    }

    fun resetReloadWindow() {
        lastSuccessfulLoadMs = 0L
    }

    suspend fun shouldSkipReload(): Boolean {
        val cached = HomeFeedCache.peek() ?: return false
        if (!cached.hasCurrentHomeLayout()) return false
        val missingRated = cached.sections.none { it.kind == HomeSectionKind.RatedSongs }
        if (missingRated && HomeFeedCache.peekHasRatedSongs() == true) return false
        return System.currentTimeMillis() - lastSuccessfulLoadMs < MIN_RELOAD_MS
    }

    /**
     * True when a network reload should replace the on-screen feed.
     * Background refresh with a visible feed must stay false to avoid scroll jank.
     */
    fun shouldPaintFreshHomeFeed(hadVisibleFeed: Boolean, withinSkipReloadWindow: Boolean): Boolean =
        !hadVisibleFeed || !withinSkipReloadWindow

    /** True only after a genuine profile switch — not on first mount. */
    fun shouldReloadHomeForProfileSwitch(previousMemberId: String?, currentMemberId: String?): Boolean {
        if (previousMemberId == null) return false
        return previousMemberId != currentMemberId
    }

    suspend fun <T> withLoadLock(block: suspend () -> T): T = loadMutex.withLock { block() }
}
