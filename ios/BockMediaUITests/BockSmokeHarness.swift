import XCTest

enum BockSmokeHarness {
    static let greetingTexts = ["Good morning", "Good afternoon", "Good evening"]

    static func assumeMainAppReady(_ app: XCUIApplication, timeout: TimeInterval = BockSmokeConfig.defaultTimeout) {
        dismissProfilePickerIfNeeded(app)
        let tabBar = app.tabBars.firstMatch
        if tabBar.waitForExistence(timeout: timeout) { return }
        for text in greetingTexts {
            if app.staticTexts[text].waitForExistence(timeout: 3) { return }
        }
        let account = app.buttons[BockTestTags.accountMenuButton]
        if account.waitForExistence(timeout: 5) { return }
        XCTFail("Main app shell not ready — configure server on device first")
    }

    static func dismissProfilePickerIfNeeded(_ app: XCUIApplication) {
        let picker = app.otherElements[BockTestTags.profilePicker]
        guard picker.waitForExistence(timeout: 1) else { return }
        let buttons = app.buttons
        if buttons["Continue unattributed"].exists {
            buttons["Continue unattributed"].tap()
            return
        }
        if buttons["Parent"].exists {
            buttons["Parent"].tap()
        }
    }

    static func tapTab(_ app: XCUIApplication, label: String) {
        app.tabBars.buttons[label].tap()
    }

    static func openAccountMenu(_ app: XCUIApplication) {
        let button = app.buttons[BockTestTags.accountMenuButton]
        XCTAssertTrue(button.waitForExistence(timeout: BockSmokeConfig.defaultTimeout))
        button.tap()
    }

    static func navigateAccountRoute(_ app: XCUIApplication, title: String) {
        openAccountMenu(app)
        let item = app.buttons[title]
        if item.waitForExistence(timeout: 3) {
            item.tap()
            return
        }
        app.menuItems[title].tap()
    }

    static func waitForLoadingGone(_ app: XCUIApplication, timeout: TimeInterval = BockSmokeConfig.defaultTimeout) {
        let loading = app.otherElements[BockTestTags.screenLoading]
        guard loading.exists else { return }
        _ = loading.waitForNonExistence(timeout: timeout)
    }

    static func navigateBackToShell(_ app: XCUIApplication) {
        let deadline = Date().addingTimeInterval(BockSmokeConfig.defaultTimeout)
        while Date() < deadline {
            if app.tabBars.firstMatch.exists { return }
            if app.navigationBars.buttons["Done"].exists {
                app.navigationBars.buttons["Done"].tap()
            } else if app.navigationBars.buttons["Back"].exists {
                app.navigationBars.buttons["Back"].tap()
            } else {
                break
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

    static func waitForSearchResults(_ app: XCUIApplication) {
        let results = app.otherElements[BockTestTags.searchResults]
        XCTAssertTrue(results.waitForExistence(timeout: BockSmokeConfig.defaultTimeout))
        waitForLoadingGone(app)
    }

    static func typeSearchQuery(_ app: XCUIApplication, query: String) {
        let field = app.textFields[BockTestTags.searchField]
        if field.waitForExistence(timeout: 5) {
            field.tap()
            field.typeText(query)
            return
        }
        let placeholder = app.textFields["What do you want to listen to?"]
        XCTAssertTrue(placeholder.waitForExistence(timeout: 5))
        placeholder.tap()
        placeholder.typeText(query)
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
