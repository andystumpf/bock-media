import SwiftUI
import UIKit

@main
struct BockMediaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var appState = AppState()

    init() {
        ArtworkImageCache.configureSharedURLCache()
        configureAppearance()
        BackgroundDownloadScheduler.register()
        configureQuickActions()
    }

    var body: some Scene {
        WindowGroup {
            RootView(appState: appState)
        }
    }

    private func configureAppearance() {
        let tab = UITabBarAppearance()
        tab.configureWithOpaqueBackground()
        tab.backgroundColor = UIColor(red: 0x18 / 255, green: 0x18 / 255, blue: 0x18 / 255, alpha: 1)
        let green = UIColor(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255, alpha: 1)
        let inactiveIcon = UIColor(red: 0.72, green: 0.74, blue: 0.78, alpha: 1)
        for layout in [
            tab.stackedLayoutAppearance,
            tab.inlineLayoutAppearance,
            tab.compactInlineLayoutAppearance,
        ] {
            layout.normal.iconColor = inactiveIcon
            layout.selected.iconColor = green
        }
        UITabBar.appearance().standardAppearance = tab
        UITabBar.appearance().scrollEdgeAppearance = tab

        let nav = UINavigationBarAppearance()
        nav.configureWithOpaqueBackground()
        nav.backgroundColor = UIColor.black
        nav.titleTextAttributes = [.foregroundColor: UIColor(red: 0xE8 / 255, green: 0xEC / 255, blue: 0xF4 / 255, alpha: 1)]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().tintColor = UIColor(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255, alpha: 1)

        UITableView.appearance().backgroundColor = .clear
    }

    private func configureQuickActions() {
        UIApplication.shared.shortcutItems = [
            UIApplicationShortcutItem(
                type: "com.bockmedia.nowplaying",
                localizedTitle: "Now Playing",
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(templateImageName: BockIcons.playArrow.assetName),
                userInfo: nil
            ),
            UIApplicationShortcutItem(
                type: "com.bockmedia.search",
                localizedTitle: "Search",
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(templateImageName: BockIcons.search.assetName),
                userInfo: nil
            ),
            UIApplicationShortcutItem(
                type: "com.bockmedia.downloads",
                localizedTitle: "Downloads",
                localizedSubtitle: nil,
                icon: UIApplicationShortcutIcon(templateImageName: BockIcons.download.assetName),
                userInfo: nil
            ),
        ]
    }
}
