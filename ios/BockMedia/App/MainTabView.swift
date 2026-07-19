import SwiftUI

struct MainTabView: View {
    @ObservedObject var appState: AppState
    @StateObject private var miniModel = MiniNowPlayingModel()
    @State private var selectedTab = 0
    @State private var accountRoute: AccountRoute?
    @State private var showNowPlaying = false
    @State private var showListenAgent = false

    var body: some View {
        VisibleDownloadStatusesProvider(appState: appState) {
            tabContent
        }
    }

    private var tabContent: some View {
        Group {
            switch selectedTab {
            case 0:
                tabShell(showAccount: false) {
                    HomeView(
                        appState: appState,
                        accountRoute: $accountRoute,
                        onOpenListenAgent: { showListenAgent = true }
                    )
                }
            case 1:
                tabShell(showAccount: true) { SearchView(appState: appState) }
            case 2:
                tabShell(showAccount: true) { LibraryView(appState: appState) }
            case 3:
                tabShell(showAccount: false) { DownloadsView(appState: appState, embeddedInTab: true) }
            case 4:
                tabShell(showAccount: true) { AutomationsView(appState: appState) }
            default:
                EmptyView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BockColors.black)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                MiniNowPlayingBar(
                    appState: appState,
                    model: miniModel,
                    onOpen: { showNowPlaying = true },
                    onControl: { dev, action in
                        await miniControl(dev: dev, action: action)
                    }
                )
                BockBottomNavBar(selectedTab: $selectedTab)
            }
            .background(BockColors.surfaceVariant.ignoresSafeArea(edges: .bottom))
        }
        .fullScreenCover(isPresented: $showNowPlaying) {
            NavigationStack {
                NowPlayingView(appState: appState)
            }
        }
        .fullScreenCover(isPresented: $showListenAgent) {
            ListenAgentView(appState: appState)
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
                    .padding(.bottom, miniModel.device != nil ? 88 : 56)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                            if appState.toast == toast { appState.toast = nil }
                        }
                    }
            }
        }
        .onAppear {
            Task.detached(priority: .utility) {
                await OfflineDownloadManager.shared.refresh()
            }
            miniModel.start(repository: appState.repository, remoteOk: appState.remoteOk)
            Task { await processPendingControls() }
            UITestSupport.applyLaunchOverrides(appState: appState)
            if UITestSupport.isEnabled, ProcessInfo.processInfo.arguments.contains("-NowPlayingPreview") {
                LocalPlaybackController.shared.installUITestPreviewIfNeeded(force: true)
                if ProcessInfo.processInfo.arguments.contains("-NowPlayingVideoPreview") {
                    appState.preferences.nowPlayingVideo = true
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    showNowPlaying = true
                }
            }
        }
        .onChange(of: appState.uitestSelectedTab) { _, tab in
            if let tab {
                selectedTab = tab
                appState.uitestSelectedTab = nil
            }
        }
        .onChange(of: appState.uitestResetGeneration) { _, _ in
            accountRoute = nil
            selectedTab = 0
        }
        .onChange(of: appState.pendingPlayTarget) { _, target in
            if target != nil {
                accountRoute = nil
                showNowPlaying = false
            }
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
        case .family:
            FamilyView(appState: appState)
        case .analytics:
            AnalyticsView(appState: appState)
        case .driving:
            DrivingModeView(appState: appState)
        case .about:
            AboutView(appState: appState)
        }
    }

    @ViewBuilder
    private func tabShell<Content: View>(
        showAccount: Bool = false,
        @ViewBuilder content: () -> Content
    ) -> some View {
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
                        PlaylistDetailView(
                            appState: appState,
                            playlistId: id,
                            suggestHomePin: appState.suggestHomePinPlaylistId == id
                        )
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
                    case .releaseRadar:
                        ReleaseRadarView(appState: appState)
                    case .sonicAdventure:
                        SearchSonicAdventureView(appState: appState)
                    }
                }
                .toolbar {
                    if showAccount {
                        ToolbarItem(placement: .topBarTrailing) {
                            HStack(spacing: 4) {
                                ListenAgentMicButton { showListenAgent = true }
                                AccountMenuButton(route: $accountRoute)
                            }
                        }
                    }
                }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
