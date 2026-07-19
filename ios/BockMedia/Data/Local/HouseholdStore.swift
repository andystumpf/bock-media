import Foundation

/// Server-backed household member list — source of truth is `GET /api/household`.
@MainActor
enum HouseholdStore {
    static let changedNotification = Notification.Name("BockHouseholdChanged")

    private(set) static var cached: HouseholdResponse?
    private(set) static var revision = 0
    private(set) static var lastLoadError: String?

    static func members() -> [HouseholdMember] {
        cached?.members ?? []
    }

    static func memberExists(_ id: String?) -> Bool {
        guard let id, !id.isEmpty else { return false }
        return members().contains { $0.id == id }
    }

    @discardableResult
    static func refresh(repository: BockMediaRepository, force: Bool = false) async -> HouseholdResponse {
        if force {
            repository.invalidateAPI()
        }
        do {
            let fresh = try await repository.household()
            lastLoadError = nil
            apply(fresh)
            return fresh
        } catch {
            lastLoadError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return cached ?? HouseholdResponse()
        }
    }

    static func apply(_ response: HouseholdResponse) {
        let changed = response.members != cached?.members
            || response.deviceOwners != cached?.deviceOwners
            || response.clientBindings != cached?.clientBindings
        cached = response
        guard changed else { return }
        revision += 1
        NotificationCenter.default.post(name: changedNotification, object: nil)
    }
}
