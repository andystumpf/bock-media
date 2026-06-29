import Foundation

final class AppPreferences: ObservableObject {
    private enum Key {
        static let localURL = "local_server_url"
        static let externalURL = "external_server_url"
        static let adminUser = "admin_user"
        static let adminPass = "admin_pass"
        static let mobileToken = "mobile_token"
        static let rememberMe = "remember_me"
        static let hasConnected = "has_connected"
        static let downloadWifiOnly = "download_wifi_only"
        static let lastDevice = "last_device"
    }

    private let defaults = UserDefaults.standard

    @Published var rememberMe: Bool {
        didSet { defaults.set(rememberMe, forKey: Key.rememberMe) }
    }

    init() {
        if defaults.object(forKey: Key.rememberMe) == nil {
            rememberMe = true
        } else {
            rememberMe = defaults.bool(forKey: Key.rememberMe)
        }
    }

    var hasConnectedBefore: Bool {
        defaults.bool(forKey: Key.hasConnected)
    }

    func setHasConnected(_ connected: Bool) {
        defaults.set(connected, forKey: Key.hasConnected)
    }

    var localServerURL: String? {
        get { defaults.string(forKey: Key.localURL)?.nilIfBlank }
        set { defaults.set(newValue, forKey: Key.localURL) }
    }

    var externalServerURL: String? {
        get { defaults.string(forKey: Key.externalURL)?.nilIfBlank }
        set { defaults.set(newValue, forKey: Key.externalURL) }
    }

    var adminUser: String? {
        get { KeychainStore.read(Key.adminUser) ?? defaults.string(forKey: Key.adminUser)?.nilIfBlank }
        set {
            KeychainStore.write(Key.adminUser, value: newValue)
            if newValue == nil { defaults.removeObject(forKey: Key.adminUser) }
        }
    }

    var adminPass: String? {
        get { KeychainStore.read(Key.adminPass) }
        set { KeychainStore.write(Key.adminPass, value: newValue) }
    }

    var mobileToken: String? {
        get { KeychainStore.read(Key.mobileToken) ?? defaults.string(forKey: Key.mobileToken)?.nilIfBlank }
        set { KeychainStore.write(Key.mobileToken, value: newValue) }
    }

    var lastDevice: String? {
        get { defaults.string(forKey: Key.lastDevice)?.nilIfBlank }
        set { defaults.set(newValue, forKey: Key.lastDevice) }
    }

    func applyBuildServerURLs() {
        localServerURL = BuildConfig.localServerURL.nilIfBlank
        externalServerURL = BuildConfig.externalServerURL.nilIfBlank
    }

    func applyBuildDefaultsIfEmpty() {
        applyBuildServerURLs()
        if adminUser?.isEmpty != false {
            adminUser = BuildConfig.adminUser.nilIfBlank
            adminPass = BuildConfig.adminPassword.nilIfBlank
        }
        if mobileToken?.isEmpty != false {
            mobileToken = BuildConfig.mobileApiToken.nilIfBlank
        }
    }

    func setCredentials(user: String?, pass: String?, token: String?) {
        adminUser = user?.trimmingCharacters(in: .whitespacesAndNewlines)
        adminPass = pass
        mobileToken = token?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank
    }

    func clearCredentialsIfNotRemembered() {
        guard !rememberMe else { return }
        adminUser = nil
        adminPass = nil
        mobileToken = nil
    }

    func localHosts() -> Set<String> {
        ServerURL.localHosts(localURL: localServerURL)
    }

    var searchAllLibraries: Bool {
        get {
            if defaults.object(forKey: "search_all_libraries") == nil { return true }
            return defaults.bool(forKey: "search_all_libraries")
        }
        set { defaults.set(newValue, forKey: "search_all_libraries") }
    }

    var searchSourcePath: String? {
        get { defaults.string(forKey: "search_source_path")?.nilIfBlank }
        set { defaults.set(newValue, forKey: "search_source_path") }
    }

    var libraryTab: String {
        get { defaults.string(forKey: "library_tab") ?? "all" }
        set { defaults.set(newValue, forKey: "library_tab") }
    }

    var libraryViewMode: String {
        get { defaults.string(forKey: "library_view_mode") ?? "list" }
        set { defaults.set(newValue, forKey: "library_view_mode") }
    }

    var librarySortBy: String {
        get { defaults.string(forKey: "library_sort_by") ?? "recents" }
        set { defaults.set(newValue, forKey: "library_sort_by") }
    }

    var librarySortOrder: String {
        get { defaults.string(forKey: "library_sort_order") ?? "desc" }
        set { defaults.set(newValue, forKey: "library_sort_order") }
    }
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
