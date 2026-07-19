import XCTest
@testable import BockMedia

/// Guards against post-launch freeze and scroll jank regressions (v2.6.172–174).
final class HomeStartupRegressionTests: XCTestCase {
    func testShouldPaintFreshHomeFeed_firstPaintAlwaysReplaces() {
        XCTAssertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed: false, withinSkipReloadWindow: true))
        XCTAssertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed: false, withinSkipReloadWindow: false))
    }

    func testShouldPaintFreshHomeFeed_visibleFeedWithinSkipWindow_staysSilent() {
        XCTAssertFalse(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed: true, withinSkipReloadWindow: true))
    }

    func testShouldPaintFreshHomeFeed_visibleFeedOutsideSkipWindow_replaces() {
        XCTAssertTrue(HomeLoadCoordinator.shouldPaintFreshHomeFeed(hadVisibleFeed: true, withinSkipReloadWindow: false))
    }

    func testShouldReloadHomeForProfileSwitch_onlyOnActualSwitch() {
        XCTAssertFalse(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId: nil, currentMemberId: "a"))
        XCTAssertFalse(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId: "a", currentMemberId: "a"))
        XCTAssertTrue(HomeLoadCoordinator.shouldReloadHomeForProfileSwitch(previousMemberId: "a", currentMemberId: "b"))
    }

    func testShouldSkipResumePull_debouncesDuplicateResumePull() {
        let bootPullMs: Int64 = 1_000_000
        XCTAssertTrue(HomeLoadCoordinator.shouldSkipResumePull(lastPullCompletedMs: bootPullMs, nowMs: bootPullMs + 1_000))
        XCTAssertTrue(HomeLoadCoordinator.shouldSkipResumePull(lastPullCompletedMs: bootPullMs, nowMs: bootPullMs + 4_999))
        XCTAssertFalse(HomeLoadCoordinator.shouldSkipResumePull(lastPullCompletedMs: bootPullMs, nowMs: bootPullMs + 5_000))
        XCTAssertFalse(HomeLoadCoordinator.shouldSkipResumePull(lastPullCompletedMs: bootPullMs, nowMs: bootPullMs + 10_000))
    }

    func testShouldSkipResumePull_neverBlocksWhenNoPriorPull() {
        XCTAssertFalse(HomeLoadCoordinator.shouldSkipResumePull(lastPullCompletedMs: 0, nowMs: 9_999_999))
    }
}
