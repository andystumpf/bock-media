import XCTest

final class FamilyJourneyTests: XCTestCase {
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

    func testFamilyScreenLoads() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Family"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.familyBody].waitForExistence(timeout: 15))
    }
}
