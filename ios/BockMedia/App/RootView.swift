import SwiftUI

struct RootView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var appState: AppState

    var body: some View {
        ZStack {
            switch appState.isConnected {
            case nil:
                SplashView(message: appState.bootstrapMessage)
            case false:
                SetupView(appState: appState)
            case true:
                ProfilePickerGate(appState: appState) {
                    MainTabView(appState: appState)
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
                    Task { await ClientPrefsSync.pullAndApply(repository: appState.repository) }
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
    }
}
