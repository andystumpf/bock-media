import SwiftUI

enum BockColors {
    static let green = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)
    static let navy = Color(red: 0x30 / 255, green: 0x42 / 255, blue: 0x6A / 255)
    static let gold = Color(red: 0xE9 / 255, green: 0x9D / 255, blue: 0x1A / 255)
    /// Spotify secondary text
    static let muted = Color(red: 0xB3 / 255, green: 0xB3 / 255, blue: 0xB3 / 255)
    static let black = Color.black
    /// Spotify primary text on dark backgrounds
    static let onSurface = Color.white
    static let onSurfaceVariant = Color(red: 0xB3 / 255, green: 0xB3 / 255, blue: 0xB3 / 255)
    /// Spotify app background
    static let spotifyBackground = Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255)
    static let surfaceVariant = Color(red: 0x18 / 255, green: 0x18 / 255, blue: 0x18 / 255)
    static let elevatedSurface = Color(red: 0x28 / 255, green: 0x28 / 255, blue: 0x28 / 255)
    static let shortcutHover = Color(red: 0x3E / 255, green: 0x3E / 255, blue: 0x3E / 255)
    static let homeGradientTop = Color(red: 0x3D / 255, green: 0x5A / 255, blue: 0x45 / 255).opacity(0.35)
    static let homeGradientMid = Color(red: 0x12 / 255, green: 0x12 / 255, blue: 0x12 / 255)
    static let pillInactive = Color(red: 0x28 / 255, green: 0x28 / 255, blue: 0x28 / 255)
    static let pillActive = green
    static let miniBarTop = Color(red: 0x18 / 255, green: 0x18 / 255, blue: 0x18 / 255)
    static let sheetBg = Color(red: 0x28 / 255, green: 0x28 / 255, blue: 0x28 / 255)
    static let onPrimary = Color(red: 0x0F / 255, green: 0x14 / 255, blue: 0x19 / 255)

    static let mixAccents: [Color] = [
        Color(red: 0x50 / 255, green: 0x38 / 255, blue: 0xA0 / 255),
        Color(red: 0x8D / 255, green: 0x67 / 255, blue: 0xAB / 255),
        Color(red: 0x50 / 255, green: 0x9B / 255, blue: 0xF5 / 255),
        Color(red: 0xE9 / 255, green: 0x14 / 255, blue: 0x29 / 255),
        Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255),
        Color(red: 0xE8 / 255, green: 0x11 / 255, blue: 0x5B / 255),
        Color(red: 0x14 / 255, green: 0x8A / 255, blue: 0x08 / 255),
        Color(red: 0xD8 / 255, green: 0x40 / 255, blue: 0x00 / 255),
    ]

    static func mixAccent(for seed: String) -> Color {
        let index = abs(seed.hashValue) % mixAccents.count
        return mixAccents[index]
    }
}

struct BockBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(BockColors.spotifyBackground.ignoresSafeArea())
    }
}

struct HomeHeaderBackground: ViewModifier {
    func body(content: Content) -> some View {
        content
            .background(
                LinearGradient(
                    colors: [BockColors.homeGradientTop, BockColors.homeGradientMid, BockColors.spotifyBackground],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
    }
}

extension View {
    func bockBackground() -> some View {
        modifier(BockBackground())
    }

    func homeHeaderBackground() -> some View {
        modifier(HomeHeaderBackground())
    }
}
