#if canImport(CarPlay)
import CarPlay

/// CarPlay list + now-playing templates (dev/sideload; App Store requires entitlement).
enum CarPlayCoordinator {
    static func configure(interfaceController: CPInterfaceController) {
        let continueItem = CPListItem(text: "Continue listening", detailText: "Pick up where you left off")
        let playlistsItem = CPListItem(text: "Playlists", detailText: "Recent playlists")
        let section = CPListSection(items: [continueItem, playlistsItem])
        let template = CPListTemplate(title: "Bock Media", sections: [section])
        interfaceController.setRootTemplate(template, animated: true)
    }
}
#else
enum CarPlayCoordinator {
    static func configure(interfaceController: Any) {}
}
#endif
