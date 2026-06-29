import Foundation

/// Stable accessibility identifiers for UI / smoke tests (Debug).
enum BockTestTags {
    static let bottomNav = "bock_bottom_nav"
    static let navHome = "bock_nav_home"
    static let navSearch = "bock_nav_search"
    static let navLibrary = "bock_nav_library"
    static let navAutomations = "bock_nav_automations"

    static let screenLoading = "bock_screen_loading"
    static let homeFeed = "bock_home_feed"
    static let homeGreeting = "bock_home_greeting"
    static let searchField = "bock_search_field"
    static let searchResults = "bock_search_results"
    static let libraryList = "bock_library_list"
    static let automationsContent = "bock_automations_content"
    static let analyticsBody = "bock_analytics_body"
    static let analyticsTotalPlays = "bock_analytics_total_plays"
    static let accountMenuButton = "bock_account_menu_button"
    static let settingsBody = "bock_settings_body"
    static let settingsWifiOnly = "bock_settings_wifi_only"
    static func settingsContinue(_ value: String) -> String { "bock_settings_continue_\(value)" }
    static let profilePicker = "bock_profile_picker"
    static let clientId = "bock_client_id"
    static let setupScreen = "bock_setup_screen"
}
