import XCTest

final class DownloadsJourneyTests: XCTestCase {
    private var app: XCUIApplication!

    override class func setUp() {
        super.setUp()
        let shared = XCUIApplication()
        shared.launchEnvironment["UITESTING"] = "1"
        shared.launchArguments = ["-UITesting"]
        shared.terminate()
        shared.launch()
        BockSmokeHarness.assumeMainAppReady(shared)
    }

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        BockSmokeHarness.navigateBackToShell(app)
    }

    func testDownloadsTabList() {
        BockSmokeHarness.tapTab(app, label: "Downloads")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.downloadsList].waitForExistence(timeout: 15))
    }

    func testDownloadsWifiOnlySettings() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Settings"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.settingsBody].exists)
        XCTAssertTrue(app.switches[BockTestTags.settingsWifiOnly].exists)
    }
}
