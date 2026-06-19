import XCTest

/// UI tests kept server-agnostic so they stay green on a CI runner with no live
/// backend. With no reachable server the app settles on Splash → Setup; with a
/// configured dev server it reaches the main tab bar. The tests assert behavior
/// that holds in either case and exercise the Setup form when it is presented.
final class BockMediaUITests: XCTestCase {
    private let stableStateTimeout: TimeInterval = 30

    override func setUp() {
        continueAfterFailure = false
    }

    private func launchedApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        return app
    }

    private func setupSignInButton(_ app: XCUIApplication) -> XCUIElement {
        app.buttons["Sign in"]
    }

    private func reachedMainTabs(_ app: XCUIApplication) -> Bool {
        app.tabBars.firstMatch.exists
    }

    /// The app launches and settles into a known stable surface (Setup or main tabs)
    /// without crashing — a stronger guarantee than "is running foreground".
    func testLaunchReachesKnownState() {
        let app = launchedApp()
        let signIn = setupSignInButton(app)
        let title = app.staticTexts["Bock Media"]

        let deadline = Date().addingTimeInterval(stableStateTimeout)
        var settled = false
        while Date() < deadline {
            if signIn.exists || title.exists || reachedMainTabs(app) {
                settled = true
                break
            }
            usleep(250_000)
        }

        XCTAssertTrue(settled, "App never reached Setup or the main tab bar")
        XCTAssertEqual(app.state, .runningForeground)
    }

    /// When no server is reachable the Setup screen is shown; verify its key controls
    /// exist and the credential fields accept input. Skips when a dev server connects
    /// straight to the main tabs.
    func testSetupFormIsInteractive() throws {
        let app = launchedApp()
        let signIn = setupSignInButton(app)

        guard signIn.waitForExistence(timeout: stableStateTimeout) else {
            throw XCTSkip("Setup screen not shown (a server connected to main tabs)")
        }

        XCTAssertTrue(app.staticTexts["Bock Media"].exists)
        XCTAssertTrue(app.switches["Remember me"].exists)

        // The mobile API token field should accept typed input.
        let tokenField = app.textFields["Mobile API token"]
        XCTAssertTrue(tokenField.waitForExistence(timeout: 5))
        tokenField.tap()
        tokenField.typeText("uitest-token")
        XCTAssertTrue(signIn.isHittable)
    }

    /// Setup is presented inside a scroll view; confirm it scrolls and the primary
    /// action survives interaction. Skips when not on the Setup screen.
    func testSetupScrolls() throws {
        let app = launchedApp()
        let signIn = setupSignInButton(app)

        guard signIn.waitForExistence(timeout: stableStateTimeout) else {
            throw XCTSkip("Setup screen not shown")
        }

        app.swipeUp()
        app.swipeDown()
        XCTAssertTrue(signIn.exists)
        XCTAssertEqual(app.state, .runningForeground)
    }
}
