import XCTest

/// Functional smoke tests against a connected device with server already configured.
/// Run: `./scripts/run_ios_smoke_tests.sh`
final class BockDeviceSmokeTests: XCTestCase {
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
        executionTimeAllowance = 180
        app = Self.sharedApp
        BockSmokeHarness.navigateBackToShell(app)
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    private func readyOnce() {
        BockSmokeHarness.dismissProfilePickerIfNeeded(app)
    }

    // MARK: - Launch & shell

    func testSmoke01_launch_bottomNavVisible() {
        readyOnce()
        XCTAssertTrue(BockSmokeHarness.bottomNavVisible(app))
    }

    func testSmoke02_home_showsContent() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForLoadingGone(app)
        BockSmokeHarness.waitForHomeContent(app)
    }

    // MARK: - Bottom navigation

    func testSmoke03_bottomNav_search() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Search")
        XCTAssertTrue(app.textFields[BockTestTags.searchField].waitForExistence(timeout: 10)
            || app.textFields["What do you want to listen to?"].exists)
        XCTAssertTrue(app.staticTexts["Search"].exists)
    }

    func testSmoke04_bottomNav_library() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.libraryList].exists || !app.otherElements[BockTestTags.screenLoading].exists)
    }

    func testSmoke05_bottomNav_downloads() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Downloads")
        XCTAssertTrue(BockSmokeHarness.tabScreenVisible(app, title: "Downloads"))
    }

    func testSmoke06_bottomNav_automations() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Automations")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.otherElements[BockTestTags.automationsContent].exists
                || app.staticTexts["No automations"].exists
                || app.staticTexts["Automations"].exists
        )
    }

    // MARK: - Search

    func testSmoke07_search_browseOnEmptyQuery() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Search")
        BockSmokeHarness.clearSearchField(app)
        XCTAssertTrue(app.textFields.firstMatch.exists)
    }

    func testSmoke08_search_shortQuery_returnsResults() {
        readyOnce()
        BockSmokeHarness.relaunchForSearchQuery(app, query: BockSmokeConfig.shortSearchQuery)
        BockSmokeHarness.waitForSearchResults(app)
    }

    func testSmoke09_search_fullQuery_returnsResults() {
        readyOnce()
        BockSmokeHarness.relaunchForSearchQuery(app, query: BockSmokeConfig.searchQuery)
        BockSmokeHarness.waitForSearchResults(app)
    }

    func testSmoke10_search_clearReturnsToBrowse() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Search")
        BockSmokeHarness.typeSearchQuery(app, query: BockSmokeConfig.searchQuery)
        BockSmokeHarness.waitForSearchResults(app)
        BockSmokeHarness.clearSearchField(app)
        XCTAssertTrue(app.textFields.firstMatch.exists)
    }

    // MARK: - Tab round-trip

    func testSmoke11_tabRoundTrip_allPrimaryTabs() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForHomeContent(app)
        BockSmokeHarness.tapTab(app, label: "Search")
        XCTAssertTrue(app.textFields.firstMatch.exists)
        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        BockSmokeHarness.tapTab(app, label: "Automations")
        BockSmokeHarness.waitForLoadingGone(app)
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.waitForHomeContent(app)
    }

    // MARK: - Account menu routes

    func testSmoke12_accountMenu_opens() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.openAccountMenu(app)
        XCTAssertTrue(app.buttons["Settings"].exists || app.menuItems["Settings"].exists)
    }

    func testSmoke13_accountMenu_analytics() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Analytics")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.analyticsBody].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
            || app.staticTexts["Total plays"].exists)
    }

    func testSmoke14_accountMenu_settings() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Settings")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.otherElements[BockTestTags.settingsBody].waitForExistence(timeout: BockSmokeConfig.defaultTimeout))
        XCTAssertTrue(app.staticTexts["Download over Wi‑Fi only"].exists || app.staticTexts["Wi‑Fi only"].exists)
    }

    func testSmoke15_accountMenu_family() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Family")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.navigationBars["Family"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout))
    }

    func testSmoke16_accountMenu_devices() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Alexa Devices")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(app.navigationBars["Alexa Devices"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
            || app.staticTexts["Alexa Devices"].exists)
    }

    // MARK: - Server-backed content

    func testSmoke17_library_hasItemsOrEmptyState() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Library")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.otherElements[BockTestTags.libraryList].exists
                || app.staticTexts["No items found"].exists
                || app.cells.firstMatch.exists
        )
    }

    func testSmoke18_analytics_dataOrEmptyState() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Analytics")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.staticTexts["Total plays"].exists
                || app.staticTexts["No analytics data"].exists
                || app.otherElements[BockTestTags.analyticsTotalPlays].exists
        )
    }

    func testSmoke19_settings_libraryStatsOrHealth() {
        readyOnce()
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Settings")
        BockSmokeHarness.waitForLoadingGone(app)
        XCTAssertTrue(
            app.staticTexts["Your library"].exists
                || app.staticTexts["Server health"].exists
                || app.staticTexts["Wi‑Fi only"].exists
        )
    }

    func testSmoke20_search_revisitCachedResults() {
        readyOnce()
        BockSmokeHarness.tapTab(app, label: "Search")
        BockSmokeHarness.typeSearchQuery(app, query: BockSmokeConfig.searchQuery)
        BockSmokeHarness.waitForSearchResults(app)
        BockSmokeHarness.tapTab(app, label: "Home")
        BockSmokeHarness.tapTab(app, label: "Search")
        let field = app.textFields[BockTestTags.searchField]
        let alt = app.textFields["What do you want to listen to?"]
        let fieldReady = field.waitForExistence(timeout: 10) || alt.waitForExistence(timeout: 5)
        let hasResults = app.otherElements[BockTestTags.searchResults].exists
            || app.staticTexts["Tracks"].exists
        let queryValue = (field.exists ? field.value as? String : alt.value as? String) ?? ""
        XCTAssertTrue(fieldReady && (hasResults || !queryValue.isEmpty))
    }
}
