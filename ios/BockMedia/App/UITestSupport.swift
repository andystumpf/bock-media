import SwiftUI

/// Debug-only hooks for XCUITest (profile switch, prefs flush, client id probe).
enum UITestSupport {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("-UITesting")
            || ProcessInfo.processInfo.environment["UITESTING"] == "1"
    }

    @MainActor
    static func handle(url: URL, appState: AppState) -> Bool {
        guard url.scheme == "bockmedia", url.host == "uitest" else { return false }
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let params = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") })
        switch url.path {
        case "/continue-unattributed":
            ActiveProfileStore.chooseUnattributed()
            Task {
                let previous = ActiveProfileStore.activeMemberId()
                await ClientPrefsSync.onActiveMemberChanged(
                    repository: appState.repository,
                    memberId: nil,
                    previousMemberId: previous
                )
            }
            return true
        case "/reset-shell":
            appState.uitestResetGeneration += 1
            appState.uitestSelectedTab = 0
            return true
        case "/search":
            appState.uitestSelectedTab = 1
            appState.uitestSearchQuery = params["q"] ?? ""
            appState.uitestSearchNonce += 1
            return true
        case "/tab":
            let route = (params["route"] ?? "home").lowercased()
            let index: Int = switch route {
            case "home": 0
            case "search": 1
            case "library": 2
            case "downloads": 3
            case "automations", "automation": 4
            default: 0
            }
            appState.uitestSelectedTab = index
            return true
        case "/switch-member", "/flush-prefs", "/now-playing-preview":
            guard isEnabled else { return false }
        default:
            return false
        }
        switch url.path {
        case "/switch-member":
            let memberId = params["memberId"]?.trimmingCharacters(in: .whitespacesAndNewlines)
            let previous = ActiveProfileStore.activeMemberId()
            Task {
                await ClientPrefsSync.onActiveMemberChanged(
                    repository: appState.repository,
                    memberId: memberId?.isEmpty == true ? nil : memberId,
                    previousMemberId: previous
                )
            }
            return true
        case "/flush-prefs":
            Task { try? await ClientPrefsSync.push(repository: appState.repository) }
            return true
        case "/now-playing-preview":
            appState.pendingPlayTarget = nil
            LocalPlaybackController.shared.installUITestPreviewIfNeeded(force: true)
            if params["video"] == "1" {
                appState.preferences.nowPlayingVideo = true
            } else {
                appState.preferences.nowPlayingVideo = false
            }
            appState.showNowPlayingSheet = true
            return true
        default:
            return false
        }
    }

    @MainActor
    static func applyLaunchOverrides(appState: AppState) {
        guard isEnabled else { return }
        let args = ProcessInfo.processInfo.arguments
        if let idx = args.firstIndex(of: "-UITestTab"), idx + 1 < args.count {
            let route = args[idx + 1].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let index: Int = switch route {
            case "home": 0
            case "search": 1
            case "library": 2
            case "downloads": 3
            case "automations", "automation": 4
            default: 0
            }
            appState.uitestSelectedTab = index
        }
        if let idx = args.firstIndex(of: "-UITestSearchQuery"), idx + 1 < args.count {
            let q = args[idx + 1].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !q.isEmpty else { return }
            appState.uitestSelectedTab = 1
            appState.uitestSearchQuery = q
            appState.uitestSearchNonce += 1
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
