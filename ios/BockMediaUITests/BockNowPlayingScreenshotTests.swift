import XCTest

/// Isolated Now Playing screenshots (fresh launch with preview fixture).
final class BockNowPlayingScreenshotTests: XCTestCase {
    func testScreenshot_nowPlayingVideo() {
        let app = XCUIApplication()
        app.launchArguments += ["-UITesting", "-NowPlayingPreview", "-NowPlayingVideoPreview"]
        app.launch()
        BockSmokeHarness.assumeMainAppReady(app)
        XCTAssertTrue(
            app.buttons["nowPlayingDismiss"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout),
            "Now Playing video preview did not open"
        )
        sleep(2)
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = "09-now-playing-video"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
