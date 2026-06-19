import XCTest

/// Smoke test: the app launches and reaches a foreground state without crashing.
/// Kept server-agnostic so it is green on a CI runner with no live backend
/// (it lands on Splash → Setup when no server is reachable).
final class BockMediaUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    func testAppLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertEqual(app.state, .runningForeground)
    }
}
