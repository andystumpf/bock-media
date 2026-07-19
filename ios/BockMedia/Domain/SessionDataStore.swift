import Foundation

/// Cross-tab session cache — Home, Library, and Search share one playlist/history snapshot.
enum SessionDataStore {
    private static let ttl: TimeInterval = 10 * 60

    private static var playlistsKey: String?
    private static var playlistsAt: Date?
    private static var playlists: PlaylistsResponse?

    private static var historyAt: Date?
    private static var history: StreamHistoryResponse?

    private static var dashboardAt: Date?
    private static var dashboard: DashboardQuickResponse?

    private static var analyticsAt: Date?
    private static var analytics: AnalyticsResponse?

    static func peekPlaylists(memberKey: String) -> PlaylistsResponse? {
        guard playlistsKey == memberKey,
              let at = playlistsAt,
              Date().timeIntervalSince(at) < ttl else { return nil }
        return playlists
    }

    static func putPlaylists(memberKey: String, response: PlaylistsResponse) {
        playlistsKey = memberKey
        playlistsAt = Date()
        playlists = response
    }

    static func invalidatePlaylists() {
        playlistsKey = nil
        playlistsAt = nil
        playlists = nil
    }

    static func peekHistory() -> StreamHistoryResponse? {
        guard let at = historyAt, Date().timeIntervalSince(at) < ttl else { return nil }
        return history
    }

    static func putHistory(_ response: StreamHistoryResponse) {
        historyAt = Date()
        history = response
    }

    static func peekDashboard() -> DashboardQuickResponse? {
        guard let at = dashboardAt, Date().timeIntervalSince(at) < ttl else { return nil }
        return dashboard
    }

    static func putDashboard(_ response: DashboardQuickResponse) {
        dashboardAt = Date()
        dashboard = response
    }

    static func peekAnalytics() -> AnalyticsResponse? {
        guard let at = analyticsAt, Date().timeIntervalSince(at) < ttl else { return nil }
        return analytics
    }

    static func putAnalytics(_ response: AnalyticsResponse) {
        analyticsAt = Date()
        analytics = response
    }

    static func invalidateAll() {
        invalidatePlaylists()
        historyAt = nil
        history = nil
        dashboardAt = nil
        dashboard = nil
        analyticsAt = nil
        analytics = nil
    }
}
