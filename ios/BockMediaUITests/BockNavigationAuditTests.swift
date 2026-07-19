import XCTest

/// Tier 2 — visit major routes (parity with Android BockNavigationAuditTest).
final class BockNavigationAuditTests: XCTestCase {
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

    private func auditAccessibility() {
        if #available(iOS 17.0, *) {
            try? app.performAccessibilityAudit()
        }
    }

    func testNav01_home() {
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForHomeContent(app)
        auditAccessibility()
    }

    func testNav02_search() {
        BockSmokeHarness.tapTab(app, label: "Search")
        XCTAssertTrue(app.textFields[BockTestTags.searchField].waitForExistence(timeout: 10)
            || app.textFields["What do you want to listen to?"].exists)
        auditAccessibility()
    }

    func testNav03_library() {
        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.libraryList].exists)
        auditAccessibility()
    }

    func testNav04_downloads() {
        BockSmokeHarness.tapTab(app, label: "Downloads")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.downloadsList].exists)
        auditAccessibility()
    }

    func testNav05_automations() {
        BockSmokeHarness.tapTab(app, label: "Automations")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.automationsContent].exists)
        auditAccessibility()
    }

    func testNav06_settings() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Settings"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.settingsBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav07_analytics() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Analytics"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.analyticsBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav08_family() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Family"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.familyBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav09_devices() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Alexa Devices"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.devicesBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav10_rooms() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Rooms"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.roomsBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav11_voiceLog() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Voice log"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.voiceLogBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav12_driving() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["Driving Mode"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.drivingBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav13_about() {
        BockSmokeHarness.openAccountMenu(app)
        app.staticTexts["About"].firstMatch.tap()
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.aboutBody].waitForExistence(timeout: 15))
        auditAccessibility()
    }

    func testNav14_nowPlayingPreview() {
        let url = URL(string: "bockmedia://uitest/now-playing-preview")!
        XCUIDevice.shared.system.open(url)
        sleep(2)
        XCTAssertTrue(app.otherElements[BockTestTags.nowPlayingBody].waitForExistence(timeout: 20))
        auditAccessibility()
    }
}
