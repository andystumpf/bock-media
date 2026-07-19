import SwiftUI

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var appState: AppState

    var body: some View {
        ZStack {
            switch appState.isConnected {
            case nil:
                SplashView(message: appState.bootstrapMessage)
            case .some(false):
                SetupView(appState: appState)
            case .some(true):
                ProfilePickerGate(appState: appState) {
                    MainTabView(appState: appState)
                        .overlay { UITestSupport.clientIdProbe() }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preferredColorScheme(.dark)
        .task { await appState.bootstrap() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:
                NowPlayingPollService.shared.setForeground(true)
                NotificationCenter.default.post(name: .widgetSessionShouldRefresh, object: nil)
                if appState.isConnected == true {
                    Task {
                        if !ClientPrefsSync.shouldSkipResumePull() {
                            appState.repository.invalidateEndpoint()
                            await ClientPrefsSync.pullAndApply(repository: appState.repository)
                        }
                    }
                }
            case .background:
                NowPlayingPollService.shared.setForeground(false)
                BackgroundDownloadScheduler.schedule()
            case .inactive:
                break
            @unknown default:
                break
            }
        }
        .onOpenURL { url in
            if UITestSupport.handle(url: url, appState: appState) { return }
            guard let link = DeepLink.parse(url: url) else { return }
            guard appState.isConnected == true else { return }
            if case .control(let deviceId, let action) = link {
                appState.pendingControl = (deviceId, action)
                appState.showNowPlayingSheet = true
            } else if case .play(let playlistId, let playlistName, let songPath, let songTitle) = link {
                Task { await appState.playFromWidget(
                    playlistId: playlistId,
                    playlistName: playlistName,
                    songPath: songPath,
                    songTitle: songTitle
                ) }
            } else {
                appState.pendingDeepLink = link
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: ClientPrefsSyncNotifications.profileChanged)) { _ in
            appState.noteProfileChanged()
        }
        .onReceive(NotificationCenter.default.publisher(for: HouseholdStore.changedNotification)) { _ in
            appState.noteHouseholdChanged()
        }
    }
}
