import XCTest
@testable import BockMedia

final class OfflineAndNetworkTests: XCTestCase {
    func testDownloadNotificationIdRoundTrip() {
        let id = "pl-jazz-favorites"
        let notifId = "download-" + id
        XCTAssertEqual(DownloadNotifications.collectionId(fromNotificationId: notifId), id)
    }

    func testDownloadNotificationIdRejectsUnprefixed() {
        XCTAssertNil(DownloadNotifications.collectionId(fromNotificationId: "something-else"))
    }

    func testOfflinePendingDownloadCodableRoundTrip() throws {
        let pending = OfflinePendingDownload(
            collectionId: "pl-1",
            path: "/music/a.mp3",
            fileName: "0001_a.mp3",
            destPath: "/tmp/collections/pl-1/0001_a.mp3"
        )
        let data = try JSONEncoder().encode([7: pending])
        let decoded = try JSONDecoder().decode([Int: OfflinePendingDownload].self, from: data)
        XCTAssertEqual(decoded[7]?.collectionId, "pl-1")
        XCTAssertEqual(decoded[7]?.fileName, "0001_a.mp3")
    }

    func testPickEndpointPrefersReachableLocal() {
        let chosen = ServerEndpointResolver.pickEndpoint(
            local: "http://192.168.1.10:5000",
            external: "https://example.com",
            localReachable: true,
            externalReachable: true
        )
        XCTAssertEqual(chosen, ServerURL.normalize("http://192.168.1.10:5000"))
    }

    func testPickEndpointFallsBackToExternalWhenLocalUnreachable() {
        let chosen = ServerEndpointResolver.pickEndpoint(
            local: "http://192.168.1.10:5000",
            external: "https://example.com",
            localReachable: false,
            externalReachable: true
        )
        XCTAssertEqual(chosen, ServerURL.normalize("https://example.com"))
    }

    func testPickEndpointReturnsNilWhenNothingConfigured() {
        XCTAssertNil(
            ServerEndpointResolver.pickEndpoint(
                local: nil, external: nil, localReachable: false, externalReachable: false
            )
        )
    }
}
