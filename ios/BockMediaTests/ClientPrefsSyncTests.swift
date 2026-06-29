import XCTest
@testable import BockMedia

@MainActor
final class ClientPrefsSyncTests: XCTestCase {
    func testApplyMergedRestoresSettings() {
        let prefs = AppPreferences()
        prefs.searchAllLibraries = false
        prefs.downloadWifiOnly = false
        prefs.crossfadeSeconds = 0
        prefs.continueAfterQueue = "off"
        prefs.lastDevice = nil

        ClientPrefsSync.applyMerged(prefs: prefs, merged: [
            "searchAllLibraries": true,
            "downloadWifiOnly": true,
            "crossfadeSeconds": 8,
            "continueAfterQueue": "similar",
            "lastDevice": "Kitchen Echo",
            "pinnedDevices": ["Kitchen Echo", "Office Echo"],
        ])

        XCTAssertTrue(prefs.searchAllLibraries)
        XCTAssertTrue(prefs.downloadWifiOnly)
        XCTAssertEqual(prefs.crossfadeSeconds, 8)
        XCTAssertEqual(prefs.continueAfterQueue, "similar")
        XCTAssertEqual(prefs.lastDevice, "Kitchen Echo")
        XCTAssertEqual(PinnedDevicesStore.pinned(), ["Kitchen Echo", "Office Echo"])
    }

    func testApplyMergedLibraryPrefs() {
        let prefs = AppPreferences()
        LibraryPrefsStore.save(filter: .all, viewMode: .list, sort: .recents, prefs: prefs, push: false)

        ClientPrefsSync.applyMerged(prefs: prefs, merged: [
            "libraryTab": "albums",
            "libraryViewMode": "grid",
            "librarySortBy": "name",
            "librarySortOrder": "asc",
        ])

        let loaded = LibraryPrefsStore.load(from: prefs)
        XCTAssertEqual(loaded.filter, .albums)
        XCTAssertEqual(loaded.viewMode, .grid)
        XCTAssertEqual(loaded.sort, .name)
        XCTAssertEqual(prefs.librarySortOrder, "asc")
    }

    func testApplyMergedSearchSelections() {
        let prefs = AppPreferences()
        SearchHistoryStore.clearSelections()

        ClientPrefsSync.applyMerged(prefs: prefs, merged: [
            "searchSelections": [
                ["kind": "artist", "title": "Sinatra"],
                ["kind": "playlist", "title": "Jazz", "id": "pl-1"],
            ],
        ])

        let items = SearchHistoryStore.selections()
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items[0].kind, "artist")
        XCTAssertEqual(items[0].title, "Sinatra")
        XCTAssertEqual(items[1].playlistId, "pl-1")
    }

    func testHomeTileEngagementRoundTrip() {
        HomeTileEngagement.resetForTesting()
        HomeTileEngagement.putForTesting(
            cardId: "card-a",
            entry: TileEngagementEntry(firstSeenMs: 1000, lastSelectedMs: 2000)
        )
        let exported = HomeTileEngagement.exportJson()
        XCTAssertNotNil(exported)

        HomeTileEngagement.resetForTesting()
        HomeTileEngagement.importJson(exported!)
        XCTAssertFalse(HomeTileEngagement.isStale(cardId: "card-a", nowMs: 3000))
    }
}
