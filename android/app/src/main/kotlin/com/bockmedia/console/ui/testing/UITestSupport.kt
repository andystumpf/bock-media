package com.bockmedia.console.ui.testing

import android.content.Intent
import android.net.Uri
import com.bockmedia.console.local.ActiveProfileStore
import com.bockmedia.console.local.ClientPrefsSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Mutable UI-test navigation state (debug / -UITesting builds only). */
object UITestState {
    data class Snapshot(
        val selectedTabRoute: String = "home",
        val searchQuery: String = "",
        val searchNonce: Int = 0,
        val showNowPlaying: Boolean = false,
        val resetGeneration: Int = 0,
        val failEndpoint: String? = null,
        val pendingDeepRoute: String? = null,
        val routeNonce: Int = 0,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    fun resetShell() {
        _state.value = _state.value.copy(
            selectedTabRoute = "home",
            searchQuery = "",
            searchNonce = _state.value.searchNonce + 1,
            showNowPlaying = false,
            resetGeneration = _state.value.resetGeneration + 1,
            failEndpoint = null,
            pendingDeepRoute = null,
        )
    }

    fun openSearch(query: String) {
        _state.value = _state.value.copy(
            selectedTabRoute = "search",
            searchQuery = query,
            searchNonce = _state.value.searchNonce + 1,
        )
    }

    fun openTab(route: String) {
        _state.value = _state.value.copy(selectedTabRoute = route.removePrefix("#"))
    }

    fun openNowPlayingPreview() {
        _state.value = _state.value.copy(showNowPlaying = true)
    }

    fun openRoute(path: String) {
        _state.value = _state.value.copy(
            pendingDeepRoute = path.removePrefix("#").trim(),
            routeNonce = _state.value.routeNonce + 1,
        )
    }

    /** Reset shell and queue a deep route in one state update (avoids home-route races in UI tests). */
    fun navigateRouteFromTest(path: String) {
        _state.value = _state.value.copy(
            selectedTabRoute = "home",
            searchQuery = "",
            showNowPlaying = false,
            failEndpoint = null,
            pendingDeepRoute = path.removePrefix("#").trim(),
            routeNonce = _state.value.routeNonce + 1,
            resetGeneration = _state.value.resetGeneration + 1,
        )
    }

    fun consumePendingRoute() {
        _state.value = _state.value.copy(pendingDeepRoute = null)
    }

    var failEndpoint: String?
        get() = _state.value.failEndpoint
        set(value) {
            _state.value = _state.value.copy(failEndpoint = value?.trim()?.lowercase()?.ifBlank { null })
        }
}

/** Debug-only hooks for instrumented UI tests (mirrors iOS UITestSupport). */
object UITestSupport {
    fun isEnabled(intent: Intent? = null): Boolean {
        if (intent?.getBooleanExtra("UITesting", false) == true) return true
        return System.getProperty("bock.uitesting") == "1"
    }

    suspend fun handleUri(context: android.content.Context, uri: Uri): Boolean {
        if (uri.scheme != "bockmedia" || uri.host != "uitest") return false
        when (uri.path) {
            "/continue-unattributed" -> {
                val previous = ActiveProfileStore.activeMemberId(context)
                ClientPrefsSync.onActiveMemberChanged(context, null, previous)
            }
            "/reset-shell" -> UITestState.resetShell()
            "/search" -> {
                val q = uri.getQueryParameter("q").orEmpty()
                UITestState.openSearch(q)
            }
            "/switch-member" -> {
                val memberId = uri.getQueryParameter("memberId")?.trim()?.ifEmpty { null }
                val previous = ActiveProfileStore.activeMemberId(context)
                ClientPrefsSync.onActiveMemberChanged(context, memberId, previous)
            }
            "/flush-prefs" -> ClientPrefsSync.push(context)
            "/now-playing-preview" -> UITestState.openNowPlayingPreview()
            "/tab" -> UITestState.openTab(uri.getQueryParameter("route").orEmpty().ifBlank { "home" })
            "/route" -> UITestState.navigateRouteFromTest(uri.getQueryParameter("path").orEmpty())
            "/fail" -> UITestState.failEndpoint = uri.getQueryParameter("endpoint")
            "/clear-fail" -> UITestState.failEndpoint = null
            else -> return false
        }
        return true
    }
}
