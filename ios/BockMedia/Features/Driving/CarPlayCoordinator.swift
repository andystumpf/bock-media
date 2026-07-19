#if canImport(CarPlay)
import CarPlay

/// CarPlay browse + now-playing templates (dev/sideload; App Store requires entitlement).
enum CarPlayCoordinator {
    static func configure(interfaceController: CPInterfaceController) {
        let continueItem = CPListItem(text: "Continue listening", detailText: "Recent plays")
        continueItem.handler = { _, completion in
            completion()
        }
        let playlistsItem = CPListItem(text: "Playlists", detailText: "Household playlists")
        playlistsItem.handler = { _, completion in
            completion()
        }
        let section = CPListSection(items: [continueItem, playlistsItem])
        let template = CPListTemplate(title: "Bock Media", sections: [section])
        interfaceController.setRootTemplate(template, animated: true)
    }

    static func showNowPlaying(_ interfaceController: CPInterfaceController) {
        let np = CPNowPlayingTemplate.shared
        interfaceController.pushTemplate(np, animated: true)
    }
}
#else
enum CarPlayCoordinator {
    static func configure(interfaceController: Any) {}
    static func showNowPlaying(_ interfaceController: Any) {}
}
#endif
