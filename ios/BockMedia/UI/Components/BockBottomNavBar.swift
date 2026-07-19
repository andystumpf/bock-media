import SwiftUI

/// Custom bottom navigation — matches Android `NavigationBar` (icon-only, Material icons).
struct BockBottomNavBar: View {
    @Binding var selectedTab: Int

    private struct Item: Identifiable {
        let tag: Int
        let icon: BockIcons
        let title: String
        let testId: String
        var id: Int { tag }
    }

    private let items: [Item] = [
        Item(tag: 0, icon: .home, title: "Home", testId: BockTestTags.navHome),
        Item(tag: 1, icon: .search, title: "Search", testId: BockTestTags.navSearch),
        Item(tag: 2, icon: .libraryMusic, title: "Library", testId: BockTestTags.navLibrary),
        Item(tag: 3, icon: .download, title: "Downloads", testId: BockTestTags.navDownloads),
        Item(tag: 4, icon: .schedule, title: "Automations", testId: BockTestTags.navAutomations),
    ]

    private let iconSize: CGFloat = 26
    private let barHeight: CGFloat = 49

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                Button {
                    selectedTab = item.tag
                } label: {
                    BockIcon(icon: item.icon, size: iconSize)
                        .foregroundStyle(selectedTab == item.tag ? BockColors.green : BockColors.muted)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .buttonStyle(.plain)
                .frame(maxWidth: .infinity)
                .frame(height: barHeight)
                .contentShape(Rectangle())
                .accessibilityLabel(item.title)
                .accessibilityAddTraits(selectedTab == item.tag ? [.isSelected] : [])
                .accessibilityIdentifier(item.testId)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: barHeight)
    }
}
