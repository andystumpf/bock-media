import XCTest
@testable import BockMedia

final class WidgetSnapshotLogicTests: XCTestCase {
    func testDeviceLabel_prefersThisIPhoneForLocal() {
        let local = WidgetNowPlayingItem(
            deviceId: WidgetSnapshotLogic.localDeviceId,
            deviceName: "Master Bathroom",
            track: "Song",
            artist: "Artist",
            paused: false,
            isLocal: true
        )
        XCTAssertEqual(WidgetSnapshotLogic.deviceLabel(for: local), "This iPhone")
    }

    func testOrderedForDisplay_putsLocalFirstThenPlaying() {
        let bathroom = WidgetNowPlayingItem(
            deviceId: "bath",
            deviceName: "Master Bathroom",
            track: "A",
            artist: nil,
            paused: false,
            isLocal: false
        )
        let phone = WidgetNowPlayingItem(
            deviceId: WidgetSnapshotLogic.localDeviceId,
            deviceName: "This iPhone",
            track: "B",
            artist: nil,
            paused: false,
            isLocal: true
        )
        let kitchen = WidgetNowPlayingItem(
            deviceId: "kitchen",
            deviceName: "Kitchen",
            track: "C",
            artist: nil,
            paused: true,
            isLocal: false
        )
        let ordered = WidgetSnapshotLogic.orderedForDisplay([bathroom, phone, kitchen])
        XCTAssertEqual(ordered.map(\.deviceId), [phone.deviceId, bathroom.deviceId, kitchen.deviceId])
    }

    func testControlAction_togglesFromPausedState() {
        let paused = WidgetNowPlayingItem(
            deviceId: "a",
            deviceName: "Kitchen",
            track: "T",
            artist: nil,
            paused: true,
            isLocal: false
        )
        let playing = WidgetNowPlayingItem(
            deviceId: "b",
            deviceName: "Office",
            track: "T",
            artist: nil,
            paused: false,
            isLocal: false
        )
        XCTAssertEqual(WidgetSnapshotLogic.controlAction(for: paused), "play")
        XCTAssertEqual(WidgetSnapshotLogic.controlAction(for: playing), "pause")
    }

    func testControlURL_includesDeviceAndAction() {
        let url = WidgetSnapshotLogic.controlURL(deviceId: "echo-1", action: "pause")
        XCTAssertEqual(url?.host, "control")
        let query = URLComponents(url: url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(query.first(where: { $0.name == "deviceId" })?.value, "echo-1")
        XCTAssertEqual(query.first(where: { $0.name == "action" })?.value, "pause")
    }

    func testCanControl_localAlwaysTrue() {
        let local = WidgetNowPlayingItem(
            deviceId: WidgetSnapshotLogic.localDeviceId,
            deviceName: nil,
            track: "T",
            artist: nil,
            paused: false,
            isLocal: true,
            canControl: false
        )
        XCTAssertTrue(WidgetSnapshotLogic.canControl(item: local, controlsAvailable: false))
    }

    func testDisplayable_includesLocalEvenWhenTrackEmpty() {
        let local = WidgetNowPlayingItem(
            deviceId: WidgetSnapshotLogic.localDeviceId,
            deviceName: "This iPhone",
            track: nil,
            artist: nil,
            paused: false,
            isLocal: true
        )
        XCTAssertEqual(WidgetSnapshotLogic.displayable([local]).count, 1)
    }

    func testItemsForWidgetDisplay_ordersLocalFirstAndDropsEmptyRemote() {
        let bathroom = WidgetNowPlayingItem(
            deviceId: "bath",
            deviceName: "Master Bathroom",
            track: "A",
            artist: nil,
            paused: false,
            isLocal: false
        )
        let phone = WidgetNowPlayingItem(
            deviceId: WidgetSnapshotLogic.localDeviceId,
            deviceName: "Master Bathroom",
            track: "B",
            artist: nil,
            paused: false,
            isLocal: true
        )
        let emptyRemote = WidgetNowPlayingItem(
            deviceId: "ghost",
            deviceName: "Office",
            track: nil,
            artist: nil,
            paused: true,
            isLocal: false
        )
        let result = WidgetSnapshotLogic.itemsForWidgetDisplay([bathroom, phone, emptyRemote])
        XCTAssertEqual(result.map(\.deviceId), [phone.deviceId, bathroom.deviceId])
        XCTAssertEqual(WidgetSnapshotLogic.deviceLabel(for: result[0]), "This iPhone")
    }

    func testOpenDeviceURL_includesDeviceId() {
        let url = WidgetSnapshotLogic.openDeviceURL(deviceId: WidgetSnapshotLogic.localDeviceId)
        let query = URLComponents(url: url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(url?.host, "nowplaying")
        XCTAssertEqual(query.first(where: { $0.name == "deviceId" })?.value, WidgetSnapshotLogic.localDeviceId)
    }
}
