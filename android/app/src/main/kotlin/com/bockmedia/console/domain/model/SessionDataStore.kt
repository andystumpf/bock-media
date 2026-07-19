package com.bockmedia.console.domain.model

import com.bockmedia.console.data.api.dto.AnalyticsResponse
import com.bockmedia.console.data.api.dto.DashboardQuickResponse
import com.bockmedia.console.data.api.dto.PlaylistsResponse
import com.bockmedia.console.data.api.dto.StreamHistoryResponse

/** Cross-tab session cache — Home, Library, and Search share one playlist/history snapshot. */
object SessionDataStore {
    private const val TTL_MS = 10 * 60 * 1000L

    private var playlistsKey: String? = null
    private var playlistsAtMs: Long = 0
    private var playlists: PlaylistsResponse? = null

    private var historyAtMs: Long = 0
    private var history: StreamHistoryResponse? = null

    private var dashboardAtMs: Long = 0
    private var dashboard: DashboardQuickResponse? = null

    private var analyticsAtMs: Long = 0
    private var analytics: AnalyticsResponse? = null

    fun peekPlaylists(memberKey: String): PlaylistsResponse? {
        if (playlistsKey != memberKey || System.currentTimeMillis() - playlistsAtMs > TTL_MS) return null
        return playlists
    }

    fun putPlaylists(memberKey: String, response: PlaylistsResponse) {
        playlistsKey = memberKey
        playlistsAtMs = System.currentTimeMillis()
        playlists = response
    }

    fun invalidatePlaylists() {
        playlistsKey = null
        playlists = null
        playlistsAtMs = 0
    }

    fun peekHistory(): StreamHistoryResponse? =
        if (System.currentTimeMillis() - historyAtMs <= TTL_MS) history else null

    fun putHistory(response: StreamHistoryResponse) {
        historyAtMs = System.currentTimeMillis()
        history = response
    }

    fun peekDashboard(): DashboardQuickResponse? =
        if (System.currentTimeMillis() - dashboardAtMs <= TTL_MS) dashboard else null

    fun putDashboard(response: DashboardQuickResponse) {
        dashboardAtMs = System.currentTimeMillis()
        dashboard = response
    }

    fun peekAnalytics(): AnalyticsResponse? =
        if (System.currentTimeMillis() - analyticsAtMs <= TTL_MS) analytics else null

    fun putAnalytics(response: AnalyticsResponse) {
        analyticsAtMs = System.currentTimeMillis()
        analytics = response
    }

    fun invalidateAll() {
        invalidatePlaylists()
        history = null
        historyAtMs = 0
        dashboard = null
        dashboardAtMs = 0
        analytics = null
        analyticsAtMs = 0
    }
}
