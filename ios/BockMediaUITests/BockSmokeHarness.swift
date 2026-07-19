import UIKit
import XCTest

enum BockSmokeHarness {
    static let greetingTexts = ["Good morning", "Good afternoon", "Good evening"]

    static func assumeMainAppReady(_ app: XCUIApplication, timeout: TimeInterval = BockSmokeConfig.defaultTimeout) {
        bypassProfilePickerViaDeepLink()
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            dismissProfilePickerIfNeeded(app)
            if app.otherElements[BockTestTags.screenLoading].exists {
                waitForLoadingGone(app, timeout: min(90, deadline.timeIntervalSinceNow))
            }
            if bottomNavVisible(app) { return }
            if app.otherElements[BockTestTags.homeGreeting].exists { return }
            for text in greetingTexts where app.staticTexts[text].exists { return }
            if app.buttons[BockTestTags.accountMenuButton].exists { return }
            usleep(200_000)
        }
        dismissProfilePickerIfNeeded(app, timeout: 5)
        if bottomNavVisible(app) { return }
        XCTFail("Main app shell not ready — unlock phone, pick a profile, or check server connection")
    }

    static func bottomNavVisible(_ app: XCUIApplication) -> Bool {
        app.buttons[BockTestTags.navHome].exists
            || app.buttons["Home"].exists
            || app.buttons[BockTestTags.navSearch].exists
            || app.tabBars.firstMatch.exists
    }

    static func dismissProfilePickerIfNeeded(_ app: XCUIApplication, timeout: TimeInterval = 25) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let pickerVisible = app.staticTexts["Who's listening?"].exists
                || app.otherElements[BockTestTags.profilePicker].exists
            if !pickerVisible, bottomNavVisible(app) { return }

            if pickerVisible {
                bypassProfilePickerViaDeepLink()
                let continueCandidates: [XCUIElement] = [
                    app.buttons[BockTestTags.profileContinueUnattributed],
                    app.buttons["Continue unattributed"],
                    app.staticTexts["Continue unattributed"],
                ]
                for candidate in continueCandidates where candidate.waitForExistence(timeout: 2) {
                    tapElement(candidate)
                    waitForLoadingGone(app, timeout: 60)
                    return
                }
                let memberButtons = app.buttons.matching(
                    NSPredicate(format: "identifier BEGINSWITH 'bock_profile_member_'")
                )
                if memberButtons.count > 0 {
                    tapElement(memberButtons.element(boundBy: 0))
                    waitForLoadingGone(app, timeout: 60)
                    return
                }
                for button in app.buttons.allElementsBoundByIndex {
                    let label = button.label
                    guard !label.isEmpty else { continue }
                    if label == "Account menu" || label == "Continue unattributed" { continue }
                    if label.hasPrefix("bock_") { continue }
                    tapElement(button)
                    waitForLoadingGone(app, timeout: 60)
                    return
                }
            }
            usleep(200_000)
        }
    }

    private static let accountSheetTitles = [
        "Analytics", "Settings", "Family", "Alexa Devices", "Downloads",
        "Voice log", "Rooms", "Driving Mode", "About", "Routines",
    ]

    static func tapTab(_ app: XCUIApplication, label: String) {
        dismissKeyboardIfNeeded(app)
        dismissProfilePickerIfNeeded(app, timeout: 5)
        let id: String = switch label {
        case "Home": BockTestTags.navHome
        case "Search": BockTestTags.navSearch
        case "Library": BockTestTags.navLibrary
        case "Downloads": BockTestTags.navDownloads
        case "Automations": BockTestTags.navAutomations
        default: ""
        }
        if !id.isEmpty {
            let byId = app.buttons[id]
            if byId.waitForExistence(timeout: 10) {
                tapElement(byId)
                return
            }
        }
        let byLabel = app.buttons[label]
        if byLabel.waitForExistence(timeout: 5) {
            tapElement(byLabel)
            return
        }
        XCTFail("Tab '\(label)' missing")
    }

    static func tabScreenVisible(_ app: XCUIApplication, title: String) -> Bool {
        app.staticTexts[title].waitForExistence(timeout: 10)
            || app.navigationBars[title].exists
    }

    static func dismissKeyboardIfNeeded(_ app: XCUIApplication) {
        guard app.keyboards.count > 0 else { return }
        for title in ["Search", "Go", "Return", "Done"] {
            let key = app.keyboards.buttons[title]
            if key.exists {
                key.tap()
                return
            }
        }
        app.tap()
    }

    static func openAccountMenu(_ app: XCUIApplication) {
        let button = app.buttons[BockTestTags.accountMenuButton]
        XCTAssertTrue(button.waitForExistence(timeout: BockSmokeConfig.defaultTimeout))
        if !button.isHittable {
            button.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        } else {
            button.tap()
        }
        _ = app.buttons["Settings"].waitForExistence(timeout: 5)
    }

    static func navigateAccountRoute(_ app: XCUIApplication, title: String) {
        openAccountMenu(app)
        let deadline = Date().addingTimeInterval(BockSmokeConfig.defaultTimeout)
        while Date() < deadline {
            for candidate in [
                app.buttons[title],
                app.menuItems[title],
                app.staticTexts[title],
                app.cells.containing(.staticText, identifier: title).firstMatch,
            ] where candidate.waitForExistence(timeout: 2) {
                tapElement(candidate)
                _ = app.navigationBars[title].waitForExistence(timeout: 10)
                return
            }
            usleep(200_000)
        }
        XCTFail("Account route '\(title)' not found")
    }

    static func waitForLoadingGone(_ app: XCUIApplication, timeout: TimeInterval = BockSmokeConfig.defaultTimeout) {
        let loading = app.otherElements[BockTestTags.screenLoading]
        guard loading.exists else { return }
        _ = loading.waitForNonExistence(timeout: timeout)
    }

    static func navigateBackToShell(_ app: XCUIApplication) {
        dismissKeyboardIfNeeded(app)
        dismissProfilePickerIfNeeded(app, timeout: 5)
        if bottomNavVisible(app), !app.staticTexts["Who's listening?"].exists {
            return
        }
        resetShell(app)
        bypassProfilePickerViaDeepLink()
        let deadline = Date().addingTimeInterval(min(BockSmokeConfig.defaultTimeout, 45))
        while Date() < deadline {
            var dismissed = false
            for title in ["Done", "Cancel", "Close"] {
                let button = app.navigationBars.buttons[title]
                if button.exists, button.isHittable {
                    button.tap()
                    usleep(400_000)
                    dismissed = true
                    break
                }
            }
            if dismissed { continue }
            if app.navigationBars.buttons["Back"].exists, app.navigationBars.buttons["Back"].isHittable {
                app.navigationBars.buttons["Back"].tap()
                usleep(400_000)
                continue
            }
            let accountSheetOpen = accountSheetTitles.contains { app.navigationBars[$0].exists }
            if !accountSheetOpen, bottomNavVisible(app) {
                dismissProfilePickerIfNeeded(app, timeout: 2)
                return
            }
            usleep(200_000)
        }
    }

    static func ensureHomeShell(_ app: XCUIApplication) {
        navigateBackToShell(app)
        assumeMainAppReady(app)
        tapTab(app, label: "Home")
        waitForHomeContent(app)
    }

    static func waitForHomeContent(_ app: XCUIApplication) {
        let deadline = Date().addingTimeInterval(BockSmokeConfig.defaultTimeout)
        while Date() < deadline {
            if app.otherElements[BockTestTags.homeGreeting].exists { return }
            if app.otherElements[BockTestTags.homeFeed].exists { return }
            if app.buttons[BockTestTags.accountMenuButton].exists { return }
            for text in greetingTexts where app.staticTexts[text].exists { return }
            usleep(200_000)
        }
    }

    static func relaunchForSearchQuery(_ app: XCUIApplication, query: String) {
        app.terminate()
        app.launchEnvironment["UITESTING"] = "1"
        app.launchArguments = ["-UITesting", "-UITestSearchQuery", query]
        app.launch()
        assumeMainAppReady(app)
    }

    static func waitForSearchResults(_ app: XCUIApplication) {
        let timeout = min(BockSmokeConfig.defaultTimeout, 45)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if searchResultsVisible(app) { break }
            if app.otherElements[BockTestTags.searchError].exists { break }
            if app.otherElements[BockTestTags.searchEmpty].exists
                || app.staticTexts["No results"].exists { break }
            usleep(300_000)
        }
        if app.otherElements[BockTestTags.searchError].exists {
            let msg = app.staticTexts.matching(
                NSPredicate(format: "identifier == %@", BockTestTags.searchError)
            ).firstMatch.label
            XCTFail("Search error: \(msg)")
        }
        if app.otherElements[BockTestTags.searchEmpty].exists || app.staticTexts["No results"].exists {
            XCTFail("Search returned no results for the query")
        }
        XCTAssertTrue(
            searchResultsVisible(app),
            "Search results did not appear — unlock phone and confirm server is reachable"
        )
        waitForLoadingGone(app, timeout: timeout)
    }

    static func searchResultsVisible(_ app: XCUIApplication) -> Bool {
        if app.otherElements[BockTestTags.searchResults].exists { return true }
        if app.tables[BockTestTags.searchResults].exists { return true }
        if app.collectionViews[BockTestTags.searchResults].exists { return true }
        if app.staticTexts["Top result"].exists { return true }
        if app.staticTexts["Tracks"].exists || app.staticTexts["Artists"].exists { return true }
        if app.staticTexts["Albums"].exists || app.staticTexts["Playlists"].exists { return true }
        let field = app.textFields[BockTestTags.searchField]
        if field.exists, let value = field.value as? String, value.count >= 2, app.cells.count > 0 { return true }
        return false
    }

    static func openSearchQueryViaDeepLink(_ app: XCUIApplication, query: String) {
        let encoded = query.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? query
        guard let url = URL(string: "bockmedia://uitest/search?q=\(encoded)") else { return }
        // Avoid app.open() — it can restart the app under test and drop in-memory shell state.
        XCUIDevice.shared.system.open(url)
        usleep(1_500_000)
    }

    static func resetShell(_ app: XCUIApplication) {
        let url = URL(string: "bockmedia://uitest/reset-shell")!
        XCUIDevice.shared.system.open(url)
        sleep(1)
    }

    static func bypassProfilePickerViaDeepLink() {
        guard let url = URL(string: "bockmedia://uitest/continue-unattributed") else { return }
        XCUIDevice.shared.system.open(url)
        usleep(800_000)
    }

    static func tapLink(_ app: XCUIApplication, title: String) {
        for _ in 0..<5 {
            if app.buttons[title].waitForExistence(timeout: 2) {
                tapElement(app.buttons[title])
                return
            }
            if app.staticTexts[title].waitForExistence(timeout: 1) {
                tapElement(app.staticTexts[title])
                return
            }
            app.swipeUp()
            usleep(250_000)
        }
        XCTFail("Missing link '\(title)'")
    }

    static func popNavigation(_ app: XCUIApplication) {
        if app.buttons["Back"].waitForExistence(timeout: 3) {
            tapElement(app.buttons["Back"])
            return
        }
        if app.navigationBars.buttons["Back"].exists {
            tapElement(app.navigationBars.buttons["Back"])
            return
        }
        app.swipeRight()
    }

    static func tapElement(_ element: XCUIElement) {
        if element.isHittable {
            element.tap()
        } else {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
    }

    static func typeSearchQuery(_ app: XCUIApplication, query: String) {
        dismissKeyboardIfNeeded(app)
        openSearchQueryViaDeepLink(app, query: query)
        tapTab(app, label: "Search")
        let field = app.textFields[BockTestTags.searchField]
        let alt = app.textFields["What do you want to listen to?"]
        let deadline = Date().addingTimeInterval(min(BockSmokeConfig.defaultTimeout, 20))
        while Date() < deadline {
            if searchResultsVisible(app) { break }
            if field.waitForExistence(timeout: 1) || alt.waitForExistence(timeout: 1) {
                let target = field.exists ? field : alt
                if target.value as? String == query || (target.value as? String)?.contains(query) == true {
                    submitSearchKeyboard(app)
                    usleep(500_000)
                }
            }
            usleep(300_000)
        }
        if !searchResultsVisible(app) {
            clearSearchField(app)
            guard field.waitForExistence(timeout: 10) || alt.waitForExistence(timeout: 5) else {
                XCTFail("Search field missing")
                return
            }
            let target = field.exists ? field : alt
            target.tap()
            target.typeText(query)
            submitSearchKeyboard(app)
        }
        waitForLoadingGone(app, timeout: 45)
    }

    private static func submitSearchKeyboard(_ app: XCUIApplication) {
        for title in ["Search", "Go", "Return", "Done"] {
            let key = app.keyboards.buttons[title]
            if key.exists {
                key.tap()
                return
            }
        }
    }

    static func clearSearchField(_ app: XCUIApplication) {
        if app.buttons["Clear text"].exists {
            app.buttons["Clear text"].tap()
            return
        }
        let field = app.textFields.matching(NSPredicate(format: "placeholderValue CONTAINS 'listen'")).firstMatch
        if field.exists {
            field.tap()
            field.press(forDuration: 1.2)
            if app.menuItems["Select All"].exists {
                app.menuItems["Select All"].tap()
            }
            field.typeText("")
        }
    }

    static func switchToMember(_ app: XCUIApplication, memberId: String) {
        let url = URL(string: "bockmedia://uitest/switch-member?memberId=\(memberId)")!
        XCUIDevice.shared.system.open(url)
        sleep(2)
        ensureHomeShell(app)
    }

    static func clientId(from app: XCUIApplication) -> String {
        let el = app.otherElements[BockTestTags.clientId]
        XCTAssertTrue(el.waitForExistence(timeout: 10))
        return el.label
    }
}
