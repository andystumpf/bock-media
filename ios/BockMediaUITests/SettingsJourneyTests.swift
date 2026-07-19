import XCTest

final class SettingsJourneyTests: XCTestCase {
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

    func testSettingsBodyAndContinuePicker() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Settings"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.settingsBody].waitForExistence(timeout: 15))
        XCTAssertTrue(app.otherElements[BockTestTags.settingsContinue("picker")].exists)
    }
}
