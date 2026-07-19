import XCTest

final class HomeJourneyTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchEnvironment["UITESTING"] = "1"
        app.launchArguments = ["-UITesting"]
        app.launch()
        BockSmokeHarness.assumeMainAppReady(app)
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    func testHome_feed_loads() {
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForHomeContent(app)
    }
}
