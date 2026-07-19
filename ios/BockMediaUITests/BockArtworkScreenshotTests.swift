import XCTest

/// Captures screenshots of primary screens for artwork / tab-bar verification.
/// Run: ONLY_TEST=BockMediaUITests/BockArtworkScreenshotTests ./scripts/run_ios_smoke_tests.sh
final class BockArtworkScreenshotTests: XCTestCase {
    private var app: XCUIApplication!
    private static var sharedApp: XCUIApplication!

    override class func setUp() {
        super.setUp()
        sharedApp = XCUIApplication()
        sharedApp.launchArguments += ["-UITesting"]
        sharedApp.launch()
        BockSmokeHarness.assumeMainAppReady(sharedApp)
    }

    override func setUp() {
        continueAfterFailure = false
        app = Self.sharedApp
        BockSmokeHarness.navigateBackToShell(app)
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    private func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testScreenshots_primaryTabsAndHome() {
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForLoadingGone(app)
        BockSmokeHarness.waitForHomeContent(app)
        snap("01-home")

        BockSmokeHarness.tapTab(app, label: "Search")
        BockSmokeHarness.waitForLoadingGone(app)
        snap("02-search-browse")

        BockSmokeHarness.typeSearchQuery(app, query: BockSmokeConfig.shortSearchQuery)
        BockSmokeHarness.waitForSearchResults(app)
        snap("03-search-results")

        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        snap("04-library")

        BockSmokeHarness.tapTab(app, label: "Downloads")
        _ = app.staticTexts["Downloads"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
            || app.staticTexts["No downloads yet"].waitForExistence(timeout: 5)
        snap("05-downloads")

        BockSmokeHarness.tapTab(app, label: "Automations")
        BockSmokeHarness.waitForLoadingGone(app)
        snap("06-automations")

        BockSmokeHarness.tapTab(app, label: "Home")
        snap("07-bottom-nav-icons")
    }

    func testScreenshot_nowPlayingVideo() {
        if !app.buttons["nowPlayingDismiss"].waitForExistence(timeout: 2) {
            if app.buttons["miniNowPlayingOpen"].waitForExistence(timeout: 8) {
                app.buttons["miniNowPlayingOpen"].tap()
            } else {
                app.open(URL(string: "bockmedia://uitest/now-playing-preview?video=1")!)
            }
            XCTAssertTrue(
                app.buttons["nowPlayingDismiss"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout),
                "Now Playing did not open"
            )
        }
        let videoToggle = app.buttons["nowPlayingVideoToggle"]
        if videoToggle.waitForExistence(timeout: 8), videoToggle.label.contains("Switch to video") {
            videoToggle.tap()
        }
        sleep(5)
        snap("09-now-playing-video")
    }

    func testScreenshot_nowPlaying() {
        if !app.buttons["nowPlayingDismiss"].waitForExistence(timeout: 2) {
            app.open(URL(string: "bockmedia://uitest/now-playing-preview")!)
            XCTAssertTrue(
                app.buttons["nowPlayingDismiss"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout),
                "Now Playing preview did not open"
            )
        }
        snap("08-now-playing")
    }
}
