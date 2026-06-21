import SwiftUI

enum AccountRoute: String, Identifiable, Equatable {
    case settings, downloads, routines, recent, rooms, devices, family, analytics, driving, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .settings: return "Settings"
        case .downloads: return "Downloads"
        case .routines: return "Routines"
        case .recent: return "Voice log"
        case .rooms: return "Rooms"
        case .devices: return "Alexa Devices"
        case .family: return "Family"
        case .analytics: return "Analytics"
        case .driving: return "Driving Mode"
        case .about: return "About"
        }
    }

    var icon: BockIcons {
        switch self {
        case .settings: return .settings
        case .downloads: return .download
        case .routines: return .bolt
        case .recent: return .recordVoiceOver
        case .rooms: return .home
        case .devices: return .speaker
        case .family: return .person
        case .analytics: return .analytics
        case .driving: return .phoneAndroid
        case .about: return .settings
        }
    }

    var usesSystemIcon: Bool {
        self == .about || self == .driving
    }

    var systemIconName: String? {
        switch self {
        case .about: return "info.circle"
        case .driving: return "car.fill"
        default: return nil
        }
    }
}

struct AccountMenuButton: View {
    @Binding var route: AccountRoute?

    var body: some View {
        Menu {
            ForEach([AccountRoute.settings, .downloads, .recent, .rooms, .devices, .family, .driving, .analytics, .about]) { item in
                Button {
                    route = item
                } label: {
                    if item.usesSystemIcon, let name = item.systemIconName {
                        Label {
                            Text(item.title)
                        } icon: {
                            Image(systemName: name)
                        }
                    } else {
                        Label(item.title, icon: item.icon, size: 20)
                    }
                }
            }
        } label: {
            BockIcon(icon: .person, size: 22)
                .foregroundStyle(BockColors.onSurface)
                .padding(8)
                .background(BockColors.surfaceVariant.opacity(0.92))
                .clipShape(Circle())
        }
    }
}
