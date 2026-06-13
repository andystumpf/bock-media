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
                MainTabView(appState: appState)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .preferredColorScheme(.dark)
        .task { await appState.bootstrap() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                BackgroundDownloadScheduler.schedule()
                NotificationCenter.default.post(name: .widgetSessionShouldRefresh, object: nil)
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
