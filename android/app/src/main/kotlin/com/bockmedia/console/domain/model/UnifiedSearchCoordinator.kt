package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.SearchResponse
import com.bockmedia.console.data.repository.BockMediaRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Spotify-style search pipeline: suggest @ 1 char, fast grouped search @ 2+. */
object UnifiedSearchCoordinator {
    const val DEBOUNCE_MS = 200L
    const val TIMEOUT_MS = 15_000L
    const val SEARCH_LIMIT = 30
    const val PREVIEW = 5

    data class Outcome(
        val response: SearchResponse,
        val fromCache: Boolean = false,
        val prefixExtension: SearchResponse? = null,
    )

    /** Resolve cache layers before network. Returns null if a fetch is required. */
    fun resolveCached(query: String): Outcome? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        SearchQueryCache.get(trimmed)?.let { return Outcome(it, fromCache = true) }
        if (trimmed.length >= 2) {
            SearchQueryCache.getPrefixExtension(trimmed)?.let { prefix ->
                return Outcome(prefix, prefixExtension = prefix)
            }
        }
        return null
    }

    suspend fun fetch(
        repository: BockMediaRepository,
        query: String,
        source: String? = null,
    ): SearchResponse = withTimeout(TIMEOUT_MS) {
        val trimmed = query.trim()
        when {
            trimmed.length == 1 -> repository.searchSuggest(trimmed)
            else -> repository.search(
                q = trimmed,
                limit = SEARCH_LIMIT,
                preview = PREVIEW,
                source = source?.takeIf { it.isNotBlank() },
                fast = true,
                includeResonance = false,
                includeRooms = false,
            )
        }
    }

    fun cachePut(query: String, response: SearchResponse) {
        SearchQueryCache.put(query.trim(), response)
    }

    fun userErrorMessage(err: Throwable, remoteOk: Boolean): String = when {
        !remoteOk -> "Search unavailable — check your connection"
        err is TimeoutCancellationException -> "Search timed out — try again"
        else -> err.message?.takeIf { it.isNotBlank() } ?: "Search failed"
    }
}
