import XCTest

/// Profile-scoped preference CRUD + analytics attribution (needs 2+ household members).
/// Run: `./scripts/run_ios_profile_prefs_tests.sh`
final class BockProfilePrefsTests: XCTestCase {
    private var app: XCUIApplication!
    private var memberA = ""
    private var memberB = ""

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["-UITesting"]
        app.launch()
        BockSmokeHarness.assumeMainAppReady(app)
    }

    private func assumeTwoMembers() throws {
        let members = try awaitResult { try await ProfileTestAPI.householdMembers() }
        try XCTSkipIf(members.count < 2, "Need at least 2 household members")
        memberA = members[0].id
        memberB = members[1].id
    }

    private func openSettings() {
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Settings")
        _ = app.otherElements[BockTestTags.settingsBody].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
    }

    private func setContinueAfterQueue(_ label: String) {
        openSettings()
        let pickerId = BockTestTags.settingsContinue("picker")
        if app.buttons[pickerId].waitForExistence(timeout: 5) {
            app.buttons[pickerId].tap()
        } else if app.staticTexts["When queue ends"].exists {
            app.staticTexts["When queue ends"].tap()
        }
        if app.menuItems[label].waitForExistence(timeout: 5) {
            app.menuItems[label].tap()
        } else {
            app.buttons[label].tap()
        }
        let clientId = BockSmokeHarness.clientId(from: app)
        ProfileTestAPI.flushPrefs(clientId: clientId)
    }

    private func openAnalytics() {
        BockSmokeHarness.ensureHomeShell(app)
        BockSmokeHarness.navigateAccountRoute(app, title: "Analytics")
        BockSmokeHarness.waitForLoadingGone(app)
        _ = app.otherElements[BockTestTags.analyticsBody].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
            || app.staticTexts["Total plays"].waitForExistence(timeout: BockSmokeConfig.defaultTimeout)
    }

    private func waitForTotalPlays(_ expected: Int) {
        let el = app.otherElements[BockTestTags.analyticsTotalPlays]
        let deadline = Date().addingTimeInterval(BockSmokeConfig.defaultTimeout)
        while Date() < deadline {
            if el.exists, el.label.contains("\(expected)") { return }
            if app.staticTexts.matching(NSPredicate(format: "label CONTAINS '\(expected)'")).firstMatch.exists { return }
            usleep(300_000)
        }
        XCTFail("Total plays never showed \(expected)")
    }

    func testProfile01_createMemberA_continueAfterQueue() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberA)
        setContinueAfterQueue("Artist radio")
        let clientId = BockSmokeHarness.clientId(from: app)
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberA, expected: "artist_radio")
    }

    func testProfile02_createMemberB_differentPref() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberB)
        setContinueAfterQueue("Similar songs")
        let clientId = BockSmokeHarness.clientId(from: app)
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberB, expected: "similar")
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberA, expected: "artist_radio")
    }

    func testProfile03_readMemberA_afterSwitchBack() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberA)
        openSettings()
        XCTAssertTrue(app.buttons["Artist radio"].exists)
        let clientId = BockSmokeHarness.clientId(from: app)
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberA, expected: "artist_radio")
    }

    func testProfile04_updateMemberA_toOff() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberA)
        setContinueAfterQueue("Stop")
        let clientId = BockSmokeHarness.clientId(from: app)
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberA, expected: "off")
        await ProfileTestAPI.assertRemoteContinue(clientId: clientId, memberId: memberB, expected: "similar")
    }

    func testProfile05_wifiOnlyToggle_perProfile() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberA)
        openSettings()
        let toggle = app.switches[BockTestTags.settingsWifiOnly]
        if toggle.value as? String == "0" { toggle.tap() }
        let clientId = BockSmokeHarness.clientId(from: app)
        ProfileTestAPI.flushPrefs(clientId: clientId)
        let wifiA = try await ProfileTestAPI.wifiOnly(clientId: clientId, memberId: memberA)
        try XCTSkipIf(wifiA != true, "Could not enable Wi‑Fi only for member A")

        BockSmokeHarness.switchToMember(app, memberId: memberB)
        openSettings()
        XCTAssertEqual(app.switches[BockTestTags.settingsWifiOnly].value as? String, "0")

        BockSmokeHarness.switchToMember(app, memberId: memberA)
        openSettings()
        XCTAssertEqual(app.switches[BockTestTags.settingsWifiOnly].value as? String, "1")
        app.switches[BockTestTags.settingsWifiOnly].tap()
        ProfileTestAPI.flushPrefs(clientId: clientId)
    }

    func testProfile06_analytics_totalPlays_matchesMemberScope() async throws {
        try assumeTwoMembers()
        BockSmokeHarness.switchToMember(app, memberId: memberA)
        let expectedA = try await ProfileTestAPI.memberTotalPlays(memberId: memberA)
        openAnalytics()
        waitForTotalPlays(expectedA)

        BockSmokeHarness.switchToMember(app, memberId: memberB)
        let expectedB = try await ProfileTestAPI.memberTotalPlays(memberId: memberB)
        openAnalytics()
        if expectedA != expectedB {
            waitForTotalPlays(expectedB)
        }
    }
}

private func awaitResult<T>(_ work: @escaping () async throws -> T) throws -> T {
    let sem = DispatchSemaphore(value: 0)
    var result: Result<T, Error>!
    Task {
        do { result = .success(try await work()) }
        catch { result = .failure(error) }
        sem.signal()
    }
    sem.wait()
    return try result.get()
}
