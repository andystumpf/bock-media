import Foundation

final class AppPreferences: ObservableObject {
    private enum Key {
        static let localURL = "local_server_url"
        static let externalURL = "external_server_url"
        static let adminUser = "admin_user"
        static let adminPass = "admin_pass"
        static let mobileToken = "mobile_token"
        static let rememberMe = "remember_me"
        static let downloadWifiOnly = "download_wifi_only"
        static let lastDevice = "last_device"
    }

    private let defaults = UserDefaults.standard

    @Published var rememberMe: Bool {
        didSet { defaults.set(rememberMe, forKey: Key.rememberMe) }
    }

    init() {
        rememberMe = defaults.bool(forKey: Key.rememberMe)
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
}

private extension String {
    var nilIfBlank: String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}
