import SwiftUI

/// Debug-only hooks for XCUITest (profile switch, prefs flush, client id probe).
enum UITestSupport {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-UITesting")
    }

    @MainActor
    static func handle(url: URL, appState: AppState) -> Bool {
        guard isEnabled, url.scheme == "bockmedia", url.host == "uitest" else { return false }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let params = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") })
        switch url.path {
        case "/switch-member":
            let memberId = params["memberId"]?.trimmingCharacters(in: .whitespacesAndNewlines)
            Task {
                await ClientPrefsSync.onActiveMemberChanged(
                    repository: appState.repository,
                    memberId: memberId?.isEmpty == true ? nil : memberId
                )
            }
            return true
        case "/flush-prefs":
            Task { try? await ClientPrefsSync.push(repository: appState.repository) }
            return true
        default:
            return false
        }
    }

    @ViewBuilder
    static func clientIdProbe() -> some View {
        if isEnabled {
            Text(ClientIdStore.clientId())
                .accessibilityIdentifier(BockTestTags.clientId)
                .frame(width: 1, height: 1)
                .opacity(0.01)
        }
    }
}
