import SwiftUI

struct MainTabView: View {
    @ObservedObject var appState: AppState
    @StateObject private var miniModel = MiniNowPlayingModel()
    @State private var selectedTab = 0
    @State private var accountRoute: AccountRoute?
    @State private var showNowPlaying = false

    var body: some View {
        TabView(selection: $selectedTab) {
            tabShell { HomeView(appState: appState, accountRoute: $accountRoute) }
                .tabItem { Label("Home", icon: .home) }
                .tag(0)

            tabShell(showAccount: true) { SearchView(appState: appState) }
                .tabItem { Label("Search", icon: .search) }
                .tag(1)

            tabShell(showAccount: true) { LibraryView(appState: appState) }
                .tabItem { Label("Library", icon: .libraryMusic) }
                .tag(2)

            tabShell(showAccount: true) { AutomationsView(appState: appState) }
                .tabItem { Label("Automations", icon: .schedule) }
                .tag(3)
        }
        .tint(BockColors.green)
        .background(BockColors.black)
        .sheet(isPresented: $showNowPlaying) {
            NavigationStack {
                NowPlayingView(appState: appState)
            }
        }
        .sheet(item: $accountRoute) { route in
            NavigationStack {
                accountDestination(route)
            }
        }
        .playLauncher(appState: appState)
        .overlay(alignment: .top) {
            AlexaAuthMonitor(appState: appState)
        }
        .overlay(alignment: .bottom) {
            if let toast = appState.toast {
                Text(toast)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial)
                    .clipShape(Capsule())
                    .padding(.bottom, miniModel.device != nil ? 112 : 72)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                            if appState.toast == toast { appState.toast = nil }
                        }
                    }
            }
        }
        .onAppear {
            OfflineDownloadManager.shared.refresh()
            miniModel.start(repository: appState.repository, remoteOk: appState.remoteOk)
            Task { await processPendingControls() }
        }
        .onDisappear { miniModel.stop() }
        .onChange(of: appState.playbackFocusGeneration) { _, _ in
            Task { await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .localPlaybackDidChange)) { _ in
            Task { await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk) }
        }
        .onReceive(NotificationCenter.default.publisher(for: .widgetSessionShouldRefresh)) { _ in
            Task { await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk) }
        }
        .onChange(of: appState.pendingDeepLink) { _, link in
            guard let link else { return }
            selectedTab = link.tabIndex
            switch link {
            case .nowPlaying(let deviceId):
                if let deviceId {
                    PlaybackFocus.focusDeviceId(deviceId)
                    appState.playbackFocusGeneration = PlaybackFocus.generation
                }
                showNowPlaying = true
            case .downloads:
                accountRoute = .downloads
            case .settings:
                accountRoute = .settings
            case .analytics:
                accountRoute = .analytics
            default:
                break
            }
            appState.pendingDeepLink = nil
        }
        .onChange(of: appState.showNowPlayingSheet) { _, show in
            if show {
                showNowPlaying = true
                appState.showNowPlayingSheet = false
            }
        }
        .onChange(of: appState.pendingControl?.deviceId) { _, _ in
            Task { await processPendingControls() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .bockQuickAction)) { note in
            guard let type = note.object as? String else { return }
            switch type {
            case "com.bockmedia.nowplaying":
                showNowPlaying = true
            case "com.bockmedia.search":
                selectedTab = 1
            case "com.bockmedia.downloads":
                accountRoute = .downloads
            default:
                break
            }
        }
    }

    private func processPendingControls() async {
        if let cmd = WidgetControlQueue.dequeue() {
            await executeControl(deviceId: cmd.deviceId, action: cmd.action)
        }
        if let pending = appState.pendingControl {
            appState.pendingControl = nil
            await executeControl(deviceId: pending.deviceId, action: pending.action)
        }
    }

    private func executeControl(deviceId: String, action: String) async {
        if deviceId == LocalPlaybackIds.localPhoneDeviceId {
            switch action {
            case "play", "pause": LocalPlaybackController.shared.togglePlayPause()
            case "next": LocalPlaybackController.shared.skipNext()
            case "previous": LocalPlaybackController.shared.skipPrevious()
            case "stop": LocalPlaybackController.shared.stopPlayback()
            default: break
            }
            await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
            return
        }
        guard let devices = try? await appState.repository.nowPlayingDevices(),
              let dev = devices.items.first(where: { $0.deviceId == deviceId }),
              let alexa = try? await appState.repository.alexaRemoteDevices(),
              let serial = resolveSerial(device: dev, alexaDevices: alexa) else { return }
        _ = try? await appState.repository.deviceControl(
            deviceId: deviceId,
            deviceName: dev.deviceName ?? "",
            serial: serial,
            action: action
        )
        await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
    }

    @ViewBuilder
    private func accountDestination(_ route: AccountRoute) -> some View {
        switch route {
        case .settings:
            SettingsView(appState: appState)
        case .downloads:
            DownloadsView(appState: appState)
        case .routines:
            RoutinesView()
        case .recent:
            RecentRequestsView(appState: appState)
        case .rooms:
            RoomsView(appState: appState)
        case .devices:
            DevicesView(appState: appState)
        case .analytics:
            AnalyticsView(appState: appState)
        }
    }

    @ViewBuilder
    private func tabShell<Content: View>(showAccount: Bool = false, @ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content()
                .bockBackground()
                .navigationDestination(for: LibraryRoute.self) { route in
                    switch route {
                    case .favorites:
                        FavoritesView(appState: appState)
                    case .playlists:
                        PlaylistsView(appState: appState)
                    case .managePlaylists:
                        ManagePlaylistsView(appState: appState)
                    case .playlistDetail(let id):
                        PlaylistDetailView(appState: appState, playlistId: id)
                    case .artists:
                        ArtistsView(appState: appState)
                    case .albums(let artist):
                        AlbumsView(appState: appState, artist: artist)
                    case .songs(let artist, let album):
                        SongsView(appState: appState, artist: artist, album: album)
                    }
                }
                .navigationDestination(for: SearchRoute.self) { route in
                    switch route {
                    case .genre(let name):
                        GenreDetailView(appState: appState, genreName: name)
                    case .artist(let name):
                        ArtistDetailView(appState: appState, artistName: name)
                    case .album(let name, let artist):
                        AlbumDetailView(appState: appState, albumName: name, artist: artist)
                    }
                }
                .toolbar {
                    if showAccount {
                        ToolbarItem(placement: .topBarTrailing) {
                            AccountMenuButton(route: $accountRoute)
                        }
                    }
                }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            MiniNowPlayingBar(
                appState: appState,
                model: miniModel,
                onOpen: { showNowPlaying = true },
                onControl: { dev, action in
                    await miniControl(dev: dev, action: action)
                }
            )
        }
    }

    private func miniControl(dev: NowPlayingDeviceItem, action: String) async {
        guard let devices = try? await appState.repository.alexaRemoteDevices(),
              let serial = resolveSerial(device: dev, alexaDevices: devices) else { return }
        _ = try? await appState.repository.deviceControl(
            deviceId: dev.deviceId,
            deviceName: dev.deviceName ?? "",
            serial: serial,
            action: action
        )
        await miniModel.refreshOnFocus(repository: appState.repository, remoteOk: appState.remoteOk)
    }
}
