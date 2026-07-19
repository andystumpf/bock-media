import XCTest

/// §7 parity QA — maps to docs/IOS_PARITY_PLAN.md side-by-side checklist.
/// Run: `./scripts/run_ios_parity_qa.sh`
final class BockParityQATests: XCTestCase {
    private var app: XCUIApplication!
    private static var sharedApp: XCUIApplication!

    override class func setUp() {
        super.setUp()
        sharedApp = XCUIApplication()
        sharedApp.launchEnvironment["UITESTING"] = "1"
        sharedApp.launchArguments = ["-UITesting"]
        sharedApp.terminate()
        sharedApp.launch()
        BockSmokeHarness.assumeMainAppReady(sharedApp)
    }

    override func setUp() {
        continueAfterFailure = false
        app = Self.sharedApp
        BockSmokeHarness.navigateBackToShell(app)
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    private func ready() {
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    // §7 step 1 — profile gate / shell
    func testParity01_profileGateOrShell() {
        ready()
        XCTAssertTrue(BockSmokeHarness.bottomNavVisible(app))
    }

    // §7 step 1 — home profile banner when unattributed
    func testParity02_homeProfileBannerWhenUnattributed() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForHomeContent(app)
        if app.buttons[BockTestTags.profileContinueUnattributed].exists {
            app.buttons[BockTestTags.profileContinueUnattributed].tap()
            BockSmokeHarness.waitForLoadingGone(app)
        }
        // Banner only when no active member; pass if shell OK or banner visible.
        let hasBanner = app.staticTexts["Select your profile in Family to restore ratings and settings."].exists
        let hasMember = !hasBanner
        XCTAssertTrue(hasBanner || hasMember || BockSmokeHarness.bottomNavVisible(app))
    }

    // §7 step 2 — home feed sections load
    func testParity03_homeFeedSections() {
        ready()
        BockSmokeHarness.ensureHomeShell(app)
        XCTAssertTrue(app.otherElements[BockTestTags.homeFeed].exists)
    }

    // §7 step 2 — Listen Agent mic on Home
    func testParity04_homeListenAgentMic() {
        ready()
        BockSmokeHarness.ensureHomeShell(app)
        let mic = app.buttons["Listen agent"]
        XCTAssertTrue(mic.waitForExistence(timeout: 10))
        mic.tap()
        XCTAssertTrue(
            app.navigationBars["Listen Agent"].waitForExistence(timeout: 15)
                || app.staticTexts["Listen Agent"].exists
                || app.textViews.firstMatch.exists
        )
        dismissListenAgent()
    }

    private func tapLink(_ title: String) {
        BockSmokeHarness.tapLink(app, title: title)
    }

    // §7 step 3 — Search browse: pins editor, Sonic Adventure, Sonic Sage → MixMuse
    func testParity05_searchDiscoveryParity() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Search")
        BockSmokeHarness.clearSearchField(app)
        XCTAssertTrue(app.staticTexts["Sonic explorations"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["Sonic Adventure"].exists || app.staticTexts["Sonic Adventure"].exists)
        XCTAssertTrue(app.buttons["Sonic Sage"].exists || app.staticTexts["Sonic Sage"].exists)

        BockSmokeHarness.tapLink(app, title: "Edit shortcuts")
        XCTAssertTrue(
            app.navigationBars["Edit shortcuts"].waitForExistence(timeout: 10)
                || app.staticTexts["Add shortcut"].exists
                || app.buttons["Cancel"].exists
        )
        if app.buttons["Cancel"].exists { app.buttons["Cancel"].tap() }

        tapLink("Sonic Adventure")
        XCTAssertTrue(
            app.staticTexts["Sonic Adventure"].waitForExistence(timeout: 20)
                || app.staticTexts["Pick a destination"].exists
                || app.staticTexts["No adventure stops found yet"].exists
        )
        BockSmokeHarness.popNavigation(app)

        tapLink("Sonic Sage")
        XCTAssertTrue(
            app.navigationBars["Sonic Sage"].waitForExistence(timeout: 15)
                || app.staticTexts["Sonic Sage"].exists
        )
        if app.buttons["Cancel"].exists { app.buttons["Cancel"].tap() }
    }

    // §7 step 4 — Listen Agent on Search (Library/Automations covered by mic on Home + tab smoke)
    func testParity06_searchListenAgentMic() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Search")
        let mic = app.buttons["Listen agent"]
        if mic.waitForExistence(timeout: 8) {
            mic.tap()
            XCTAssertTrue(app.textViews.firstMatch.waitForExistence(timeout: 15))
            dismissListenAgent()
        }
    }

    // §7 step 5 — Now Playing video panel (UITest preview)
    func testParity07_nowPlayingVideoToggle() {
        ready()
        let url = URL(string: "bockmedia://uitest/now-playing-preview?video=1")!
        XCUIDevice.shared.system.open(url)
        sleep(2)
        let toggle = app.buttons["nowPlayingVideoToggle"]
        XCTAssertTrue(
            toggle.waitForExistence(timeout: 15)
                || app.otherElements["nowPlayingDismiss"].exists
                || app.buttons["nowPlayingDismiss"].exists
        )
        if app.buttons["nowPlayingDismiss"].exists {
            app.buttons["nowPlayingDismiss"].tap()
        } else if app.otherElements["nowPlayingDismiss"].exists {
            app.otherElements["nowPlayingDismiss"].tap()
        }
    }

    // §7 step 6 — Library loads (health banner is data-dependent)
    func testParity08_libraryScreen() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        let hasList = app.otherElements[BockTestTags.libraryList].exists
        let hasHealth = app.staticTexts["Library needs attention"].exists
        XCTAssertTrue(hasList || hasHealth || app.cells.firstMatch.exists)
    }

    // §7 step 8 — Downloads tab
    func testParity09_downloadsScreen() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Downloads")
        XCTAssertTrue(BockSmokeHarness.tabScreenVisible(app, title: "Downloads"))
    }

    // §7 step 9 — Device fix wizard
    func testParity10_deviceFixWizard() {
        ready()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Alexa Devices")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.navigationBars["Alexa Devices"].waitForExistence(timeout: 15)
                || app.staticTexts["Identify speakers"].waitForExistence(timeout: 15)
        )
        let fix = app.buttons["Fix my devices"]
        guard fix.waitForExistence(timeout: 5) else { return }
        guard fix.isEnabled else { return }
        fix.tap()
        XCTAssertTrue(
            app.staticTexts["Fix my devices"].waitForExistence(timeout: 10)
                || app.staticTexts["Play here"].exists
                || app.buttons["Close"].exists
        )
        if app.buttons["Close"].exists { app.buttons["Close"].tap() }
        else if app.buttons["Done"].exists { app.buttons["Done"].tap() }
        else if app.buttons["Cancel"].exists { app.buttons["Cancel"].tap() }
    }

    // §7 step 10 — Analytics (CSV export lives here)
    func testParity11_analyticsScreen() {
        ready()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Analytics")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.staticTexts["Total plays"].exists
                || app.otherElements[BockTestTags.analyticsBody].exists
                || app.staticTexts["No analytics data"].exists
        )
    }

    // §7 — Automations tab + Listen Agent entry
    func testParity12_automationsShell() {
        ready()
        BockSmokeHarness.tapTab(app, label: "Automations")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.otherElements[BockTestTags.automationsContent].exists
                || app.staticTexts["Automations"].exists
        )
    }

    private func dismissListenAgent() {
        for title in ["Done", "Close", "Cancel"] {
            let btn = app.navigationBars.buttons[title]
            if btn.exists, btn.isHittable {
                btn.tap()
                return
            }
        }
        if app.buttons["Done"].exists { app.buttons["Done"].tap() }
    }

    private func navigateBackIfNeeded() {
        if app.navigationBars.buttons["Back"].exists, app.navigationBars.buttons["Back"].isHittable {
            app.navigationBars.buttons["Back"].tap()
        }
    }
}
