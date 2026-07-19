import Foundation

enum DeviceCatalog {
    struct Snapshot {
        let status: AlexaRemoteStatus?
        let remoteReady: Bool
        let options: [DeviceOption]
        let devices: [AlexaDevice]
        let groups: [DeviceGroup]
        let atMs: Int64
    }

    private static let ttlMs: Int64 = 60_000
    private static var cached: Snapshot?

    static func peek() -> Snapshot? { cached }

    static func isFresh(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        guard let cached else { return false }
        return now - cached.atMs < ttlMs
    }

    static func invalidate() { cached = nil }

    static func refresh(repository: BockMediaRepository, probe: Bool = false) async -> Snapshot {
        let status = try? await repository.alexaRemoteStatus()
        let remoteReady = alexaControlsAvailable(status)
        let options: [DeviceOption]
        if remoteReady {
            async let devicesTask = repository.alexaRemoteDevices()
            async let groupsTask = repository.deviceGroups()
            let devices = (try? await devicesTask) ?? []
            let groups = (try? await groupsTask)?.items ?? []
            options = DeviceOptionBuilder.build(groups: groups, devices: devices)
            let snapshot = Snapshot(
                status: status,
                remoteReady: remoteReady,
                options: options,
                devices: devices,
                groups: groups,
                atMs: Int64(Date().timeIntervalSince1970 * 1000)
            )
            cached = snapshot
            return snapshot
        } else {
            options = []
        }
        let snapshot = Snapshot(
            status: status,
            remoteReady: remoteReady,
            options: options,
            devices: [],
            groups: [],
            atMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        cached = snapshot
        return snapshot
    }
}
