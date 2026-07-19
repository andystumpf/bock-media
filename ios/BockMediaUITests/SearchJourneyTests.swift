import XCTest

final class SearchJourneyTests: XCTestCase {
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

    func testSearch_query_unified() {
        BockSmokeHarness.tapTab(app, label: "Search")
        let field = app.textFields[BockTestTags.searchField].exists
            ? app.textFields[BockTestTags.searchField]
            : app.textFields["What do you want to listen to?"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText(BockSmokeConfig.searchQuery)
        BockSmokeHarness.waitForSearchResults(app)
    }
}
