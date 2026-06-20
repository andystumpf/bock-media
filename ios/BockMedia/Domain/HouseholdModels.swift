import Foundation

// MARK: - Household DTOs (match server /api/household, /api/messages, /api/analytics/household)

struct HouseholdMember: Codable, Identifiable, Equatable, Hashable {
    var id: String
    var name: String
    var role: String
    var color: String?
    var avatar: String?
    var hasPin: Bool
    var createdAt: Double?

    var isParent: Bool { role == "parent" }
}

struct DeviceOwner: Codable, Identifiable, Equatable {
    var deviceId: String
    var deviceName: String?
    var memberId: String?
    var memberName: String?

    var id: String { deviceId }
}

struct ClientBinding: Codable, Identifiable, Equatable {
    var clientDeviceId: String
    var deviceName: String?
    var platform: String?
    var memberId: String?
    var memberName: String?

    var id: String { clientDeviceId }
}

struct HouseholdResponse: Codable {
    var members: [HouseholdMember] = []
    var deviceOwners: [DeviceOwner] = []
    var clientBindings: [ClientBinding] = []
}

struct QuietWindow: Codable, Equatable {
    var days: [Int]?
    var from: String?
    var to: String?
}

struct RoomPolicy: Codable, Equatable {
    var deviceId: String?
    var safe: Bool = false
    var allowPlaylistIds: [String] = []
    var allowExplicit: Bool = true
    var maxVolume: Int?
    var quietHours: [QuietWindow] = []
    var requireApproval: Bool = false
}

struct MemberPlayCount: Codable, Identifiable, Equatable {
    var memberId: String?
    var name: String
    var plays: Int

    var id: String { memberId ?? "unattributed" }
}

struct RoomPlayCount: Codable, Identifiable, Equatable {
    var room: String
    var plays: Int
    var id: String { room }
}

struct PlatformPlayCount: Codable, Identifiable, Equatable {
    var platform: String
    var plays: Int
    var id: String { platform }
}

struct HouseholdAnalytics: Codable {
    var totalPlays: Int = 0
    var members: [HouseholdMember] = []
    var byMember: [MemberPlayCount] = []
    var byRoom: [RoomPlayCount] = []
    var byPlatform: [PlatformPlayCount] = []
    var leaderboard: [MemberPlayCount] = []
}

struct MessageAttach: Codable, Equatable {
    var type: String?
    var id: String?
    var title: String?
}

struct FamilyMessage: Codable, Identifiable, Equatable {
    var id: String
    var fromMemberId: String?
    var fromName: String?
    var toMemberId: String?
    var toName: String?
    var scope: String?
    var text: String?
    var attach: MessageAttach?
    var ts: Double?
    var readBy: [String]?
}

struct MessagesResponse: Codable {
    var items: [FamilyMessage] = []
    var unread: Int = 0
}

// MARK: - Active profile (who is using this install)

/// Stores the household member this install is "acting as" — used to attribute
/// plays, send messages, share playlists, and approve requests.
enum ActiveProfileStore {
    private static let key = "active_member_id"

    static func activeMemberId() -> String? {
        let v = UserDefaults.standard.string(forKey: key)?.trimmingCharacters(in: .whitespaces)
        return (v?.isEmpty == false) ? v : nil
    }

    static func setActiveMember(_ id: String?) {
        if let id, !id.isEmpty {
            UserDefaults.standard.set(id, forKey: key)
        } else {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }
}
