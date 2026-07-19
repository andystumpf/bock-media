import XCTest

final class PlaybackJourneyTests: XCTestCase {
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

    func testPlaybackNowPlayingPreview() {
        let url = URL(string: "bockmedia://uitest/now-playing-preview")!
        XCUIDevice.shared.system.open(url)
        sleep(2)
        XCTAssertTrue(app.otherElements[BockTestTags.nowPlayingBody].waitForExistence(timeout: 20))
    }
}
