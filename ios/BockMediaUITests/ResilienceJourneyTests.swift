import XCTest

final class ResilienceJourneyTests: XCTestCase {
    func testResilience_shell_after_reset() {
        let app = XCUIApplication()
        app.launchEnvironment["UITESTING"] = "1"
        app.launchArguments = ["-UITesting"]
        app.launch()
        BockSmokeHarness.assumeMainAppReady(app)
        XCUIDevice.shared.system.open(URL(string: "bockmedia://uitest/reset-shell")!)
        sleep(1)
        XCTAssertTrue(BockSmokeHarness.bottomNavVisible(app))
    }
}
