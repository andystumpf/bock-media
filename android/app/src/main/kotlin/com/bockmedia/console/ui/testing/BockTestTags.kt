package com.bockmedia.console.ui.testing

/** Stable Compose test tags for instrumented UI / perf audits (debug builds). */
object BockTestTags {
    const val BOTTOM_NAV = "bock_bottom_nav"
    const val NAV_HOME = "bock_nav_home"
    const val NAV_SEARCH = "bock_nav_search"
    const val NAV_LIBRARY = "bock_nav_library"
    const val NAV_DOWNLOADS = "bock_nav_downloads"
    const val NAV_AUTOMATIONS = "bock_nav_automations"

    const val SCREEN_LOADING = "bock_screen_loading"
    const val HOME_FEED = "bock_home_feed"
    const val HOME_GREETING = "bock_home_greeting"
    const val TAB_HEADER = "bock_tab_header"
    const val SEARCH_FIELD = "bock_search_field"
    const val SEARCH_RESULTS = "bock_search_results"
    const val LIBRARY_LIST = "bock_library_list"
    const val AUTOMATIONS_CONTENT = "bock_automations_content"
    const val ANALYTICS_BODY = "bock_analytics_body"
    const val ANALYTICS_TOTAL_PLAYS = "bock_analytics_total_plays"
    const val ACCOUNT_MENU = "bock_account_menu"
    const val ACCOUNT_MENU_BUTTON = "bock_account_menu_button"
    const val SETTINGS_BODY = "bock_settings_body"
    const val SETTINGS_WIFI_ONLY = "bock_settings_wifi_only"
    fun settingsContinue(value: String) = "bock_settings_continue_$value"
    const val FAMILY_ACTING_AS = "bock_family_acting_as"
    const val SPLASH = "bock_splash"
    const val SETUP_SCREEN = "bock_setup_screen"
    const val PROFILE_PICKER = "bock_profile_picker"
}
