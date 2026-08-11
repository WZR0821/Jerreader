import UIKit
import XCTest

@MainActor
final class JerreaderSelectionUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testStandaloneTranslatorShowsDirectionModeAndProviderControls() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        let learningTab = app.tabBars.buttons["学习"]
        XCTAssertTrue(learningTab.waitForExistence(timeout: 20))
        learningTab.tap()
        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 8))

        let translateSection = app.buttons["翻译"].firstMatch
        XCTAssertTrue(translateSection.waitForExistence(timeout: 5))
        translateSection.tap()

        let mode = app.descendants(matching: .any)
            .matching(identifier: "standalone-translation-mode")
            .firstMatch
        let provider = app.descendants(matching: .any)
            .matching(identifier: "standalone-translation-provider")
            .firstMatch
        let input = app.descendants(matching: .any)
            .matching(identifier: "standalone-translation-input")
            .firstMatch
        let translateButton = app.descendants(matching: .any)
            .matching(identifier: "standalone-translate-button")
            .firstMatch

        XCTAssertTrue(mode.waitForExistence(timeout: 5))
        XCTAssertTrue(provider.exists)
        XCTAssertTrue(input.exists)
        XCTAssertTrue(translateButton.exists)
        XCTAssertTrue(app.staticTexts["自动识别"].exists)
        XCTAssertTrue(app.staticTexts["简体中文"].exists)
        XCTAssertTrue(app.staticTexts["译文会显示在这里"].exists)

        let draft = "今天想把この文章完整地翻译出来。"
        input.tap()
        input.typeText(draft)
        XCTAssertEqual(input.value as? String, draft)

        // The section switch destroys and recreates TranslateToolView. A draft
        // must survive it, while marked Chinese/Japanese input remains owned by
        // the UIKit editor until the candidate is committed.
        app.buttons["生词本"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["生词本"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["翻译"].firstMatch.tap()
        let restoredInput = app.descendants(matching: .any)
            .matching(identifier: "standalone-translation-input")
            .firstMatch
        XCTAssertTrue(restoredInput.waitForExistence(timeout: 5))
        XCTAssertEqual(restoredInput.value as? String, draft)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "standalone-translator-redesign"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    func testVocabularyStatusFilterAndDetailStayReadable() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES",
            "--jerreader-vocabulary-ui-test",
        ]
        app.launch()

        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["本"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["extraordinary"].exists)
        XCTAssertTrue(app.staticTexts["学习中"].exists)
        XCTAssertTrue(app.staticTexts["已掌握"].exists)

        let filter = app.buttons["按学习状态筛选，当前为全部状态"]
        XCTAssertTrue(filter.waitForExistence(timeout: 5))
        filter.tap()
        let known = app.buttons["已掌握"]
        XCTAssertTrue(known.waitForExistence(timeout: 5))
        known.tap()
        XCTAssertTrue(app.staticTexts["extraordinary"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["本"].exists)

        app.staticTexts["extraordinary"].tap()
        XCTAssertTrue(app.navigationBars["词条详情"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["词典释义"].exists)
        XCTAssertTrue(app.staticTexts["学习状态"].exists)
        XCTAssertTrue(app.staticTexts["最近语境"].exists)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "vocabulary-status-and-detail"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    func testJapaneseReviewLoopRevealsLearningDetailsAndSavesRating() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES",
            "--jerreader-show-learning-module",
            "--jerreader-review-ui-test",
        ]
        app.launch()

        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["日语回想"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["昨日、家族と寿司を＿＿。"].exists)
        XCTAssertTrue(app.staticTexts["たべました"].exists == false)

        let reveal = app.buttons["显示答案"]
        XCTAssertTrue(reveal.waitForExistence(timeout: 5))
        reveal.tap()
        XCTAssertTrue(app.staticTexts["食べました"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'たべました'")
        ).firstMatch.exists)
        let remembered = app.buttons["review-rating-good"]
        XCTAssertTrue(scrollUntilHittable(remembered, in: app))

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "japanese-review-answer"
        attachment.lifetime = .keepAlways
        add(attachment)

        remembered.tap()
        XCTAssertTrue(app.staticTexts["本次完成"].waitForExistence(timeout: 5))
        app.terminate()
    }

    func testLearningModuleCanBeHiddenWithoutLeavingSettings() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES",
            "--jerreader-show-learning-module",
        ]
        addTeardownBlock {
            let cleanup = XCUIApplication()
            cleanup.launchArguments = ["--jerreader-show-learning-module"]
            cleanup.launch()
            cleanup.terminate()
        }
        app.launch()

        XCTAssertTrue(app.tabBars.buttons["学习"].waitForExistence(timeout: 20))
        app.tabBars.buttons["设置"].tap()
        let toggle = app.switches["显示学习模块"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.tap()
        let learningDisappeared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.tabBars.buttons["学习"]
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [learningDisappeared], timeout: 5),
            .completed
        )
        XCTAssertTrue(app.navigationBars["设置"].exists)

        toggle.tap()
        XCTAssertTrue(app.tabBars.buttons["学习"].waitForExistence(timeout: 5))
        app.terminate()
    }

    func testIPadShellUsesWholePortraitAndLandscapeWindow() throws {
        // The window sizes below only describe an iPad. On an iPhone
        // destination this used to fail rather than skip, which made a red
        // suite the normal state and hid the failures that matter.
        try XCTSkipUnless(
            UIDevice.current.userInterfaceIdiom == .pad,
            "iPad 专用：请在 iPad 模拟器上运行"
        )

        let device = XCUIDevice.shared
        device.orientation = .portrait
        addTeardownBlock {
            device.orientation = .portrait
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15),
            "The library shell did not finish launching"
        )
        XCTAssertTrue(
            app.buttons["导入电子书"].waitForExistence(timeout: 5),
            "The always-available import toolbar action is missing"
        )

        let window = app.windows.firstMatch
        XCTAssertTrue(window.exists)
        let portraitFrame = window.frame
        XCTAssertEqual(portraitFrame.minX, 0, accuracy: 1)
        XCTAssertEqual(portraitFrame.minY, 0, accuracy: 1)
        XCTAssertGreaterThan(portraitFrame.height, portraitFrame.width)
        XCTAssertGreaterThanOrEqual(portraitFrame.width, 800)

        let portraitAttachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        portraitAttachment.name = "ipad-library-portrait"
        portraitAttachment.lifetime = .keepAlways
        add(portraitAttachment)

        device.orientation = .landscapeLeft
        let landscapeExpectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                let frame = window.frame
                return frame.width > frame.height
            },
            object: window
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [landscapeExpectation], timeout: 8),
            .completed,
            "The app window did not adapt to landscape"
        )

        let landscapeFrame = window.frame
        XCTAssertEqual(landscapeFrame.minX, 0, accuracy: 1)
        XCTAssertEqual(landscapeFrame.minY, 0, accuracy: 1)
        XCTAssertGreaterThan(landscapeFrame.width, landscapeFrame.height)
        XCTAssertGreaterThanOrEqual(landscapeFrame.width, 1_000)
        XCTAssertTrue(app.navigationBars["书架"].exists)
        XCTAssertTrue(app.buttons["导入电子书"].exists)

        // The automation coordinate space switches before CoreAnimation has
        // necessarily committed the last rotation frame. Give the separate
        // app process one short settling interval so the retained QA image is
        // a final landscape frame rather than a half-rotated transition.
        Thread.sleep(forTimeInterval: 1)
        let landscapeAttachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        landscapeAttachment.name = "ipad-library-landscape"
        landscapeAttachment.lifetime = .keepAlways
        add(landscapeAttachment)
        app.terminate()
    }

    func testPrimaryTabsKeepAConsistentFullScreenShell() throws {
        let device = XCUIDevice.shared
        device.orientation = .portrait
        addTeardownBlock {
            device.orientation = .portrait
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15)
        )

        let window = app.windows.firstMatch
        XCTAssertTrue(window.exists)
        XCTAssertEqual(window.frame.minX, 0, accuracy: 1)
        XCTAssertEqual(window.frame.minY, 0, accuracy: 1)

        let learningTab = app.tabBars.buttons["学习"]
        XCTAssertTrue(learningTab.waitForExistence(timeout: 5))
        learningTab.tap()
        XCTAssertTrue(
            app.navigationBars["学习"].waitForExistence(timeout: 5)
        )
        let learningAttachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        learningAttachment.name = "iphone-learning-refined-ui"
        learningAttachment.lifetime = .keepAlways
        add(learningAttachment)

        let settingsTab = app.tabBars.buttons["设置"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 5))
        settingsTab.tap()
        XCTAssertTrue(
            app.navigationBars["设置"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.staticTexts["默认阅读排版"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.staticTexts["界面主题"].exists)
        XCTAssertTrue(app.staticTexts["翻译与 AI"].exists)
        XCTAssertFalse(app.staticTexts["把阅读调成喜欢的样子"].exists)
        let settingsAttachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        settingsAttachment.name = "iphone-settings-refined-ui"
        settingsAttachment.lifetime = .keepAlways
        add(settingsAttachment)

        XCTAssertEqual(window.frame.minX, 0, accuracy: 1)
        XCTAssertEqual(window.frame.minY, 0, accuracy: 1)
        app.terminate()
    }

    func testBackupCenterExposesSchedulingScopeLimitsAndRestore() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15)
        )
        let settingsTab = app.tabBars.buttons["设置"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 5))
        settingsTab.tap()
        XCTAssertTrue(
            app.navigationBars["设置"].waitForExistence(timeout: 5)
        )

        let backupLink = app.staticTexts["备份与恢复"]
        if !backupLink.waitForExistence(timeout: 2) {
            app.swipeUp()
        }
        XCTAssertTrue(backupLink.waitForExistence(timeout: 5))
        backupLink.tap()
        XCTAssertTrue(
            app.navigationBars["备份中心"].waitForExistence(timeout: 5)
        )

        let automaticSwitch = app.switches["自动备份"]
        XCTAssertTrue(automaticSwitch.waitForExistence(timeout: 5))
        let policyHeader = app.staticTexts["自动策略"]
        scrollToElement(policyHeader, in: app)
        XCTAssertTrue(policyHeader.waitForExistence(timeout: 5))

        for (identifier, name) in [
            ("backup-interval-picker", "备份间隔"),
            ("backup-retention-picker", "保存时间"),
            ("backup-count-picker", "最多保留"),
            ("backup-capacity-picker", "总容量上限")
        ] {
            let element = app.descendants(matching: .any)[identifier]
            scrollToElement(element, in: app)
            XCTAssertTrue(
                element.waitForExistence(timeout: 5),
                "备份中心没有显示“\(name)”"
            )
        }
        for label in [
            "备份位置", "自动备份范围", "手动备份", "恢复",
            "当前文件夹中的备份",
        ] {
            let element = app.staticTexts[label]
            scrollToElement(element, in: app)
            XCTAssertTrue(element.waitForExistence(timeout: 5))
        }

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "iphone-backup-center"
        attachment.lifetime = .keepAlways
        add(attachment)

        app.terminate()
    }

    /// A reinstall lands on an empty shelf, which is exactly when the user has
    /// a backup to bring back and no reason to go looking in 设置 for it.
    func testEmptyShelfOffersRestoringFromABackup() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15)
        )
        let restore = app.buttons["library-restore-backup"]
        guard restore.waitForExistence(timeout: 5) else {
            throw XCTSkip("书架里已经有书，空书架入口不会出现")
        }
        XCTAssertTrue(
            app.staticTexts["装过 Jerreader 的话，选中以前的备份文件即可恢复书架、进度和备份计划。"]
                .exists
        )

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "iphone-empty-library-restore-entry"
        attachment.lifetime = .keepAlways
        add(attachment)

        restore.tap()
        // The backup centre opens the file picker by itself, and that picker is
        // another process; asserting the centre is up is what this owns.
        XCTAssertTrue(
            app.navigationBars["备份中心"].waitForExistence(timeout: 8)
        )

        app.terminate()
    }

    private func scrollToElement(
        _ element: XCUIElement,
        in app: XCUIApplication
    ) {
        for _ in 0..<5 where !element.exists || !element.isHittable {
            app.swipeUp()
        }
    }

    func testPhysicalLongPressHighlightsKanjiInHorizontalLayout() throws {
        try verifyPhysicalRubyLongPress(orientation: "horizontal")
    }

    func testPhysicalLongPressHighlightsKanjiInPublicationLayout() throws {
        try verifyPhysicalRubyLongPress(orientation: "publication")
    }

    func testUIKitLongPressFallbackHighlightsKanjiWhenNativeCallbackIsMissing() throws {
        try verifyPhysicalRubyLongPress(
            orientation: "horizontal",
            forcesNativeCallbackMiss: true
        )
    }

    func testRealBookSwitchesReliablyBetweenOriginalVerticalAndHorizontal()
        throws
    {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=publication",
            "--jerreader-selection-controls-visible",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()
        XCTAssertTrue(
            app.otherElements["selection-ruby-target"].waitForExistence(
                timeout: 35
            )
        )
        let diagnostic = app.otherElements["layout-diagnostic-status"]
        XCTAssertTrue(diagnostic.waitForExistence(timeout: 5))
        waitForLayout(
            diagnostic,
            prefix: "publication|vertical-rl|rtl|"
        )

        openReaderSettings(in: app)
        XCTAssertTrue(app.buttons["横排"].waitForExistence(timeout: 5))
        app.buttons["横排"].tap()
        app.buttons["完成"].tap()
        // The trailing variant field is the load-bearing assertion: a stale
        // `cjk-vertical` stylesheet would keep Readium's vertical pagination
        // module active even though the text itself already reads horizontally.
        waitForLayout(
            diagnostic,
            prefix: "horizontal|horizontal-tb|ltr|paginated|cjk-horizontal"
        )

        openReaderSettings(in: app)
        XCTAssertTrue(app.buttons["原版"].waitForExistence(timeout: 5))
        app.buttons["原版"].tap()
        app.buttons["完成"].tap()
        waitForLayout(
            diagnostic,
            prefix: "publication|vertical-rl|rtl|"
        )

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "reader-original-horizontal-switch-roundtrip"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    func testRealBookExposesReaderCustomizationAndBookManagementControls() throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=horizontal",
            "--jerreader-selection-controls-visible",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.otherElements["selection-ruby-target"].waitForExistence(timeout: 35)
        )
        let moreActions = app.buttons["更多阅读操作"]
        XCTAssertTrue(moreActions.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(moreActions, in: app)
        let readerSettings = app.buttons["阅读设置"]
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 5))
        readerSettings.tap()
        XCTAssertTrue(
            app.navigationBars["阅读与翻译"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.buttons["逐页"].exists || app.buttons["滚动"].exists
        )
        let customBackground = app.switches["自定义背景颜色"]
        for _ in 0 ..< 3 where !customBackground.exists {
            app.swipeUp()
        }
        XCTAssertTrue(customBackground.waitForExistence(timeout: 3))
        XCTAssertFalse(app.staticTexts["听书与跟读"].exists)
        XCTAssertFalse(app.staticTexts["朗读速度"].exists)

        let settingsAttachment = XCTAttachment(screenshot: app.screenshot())
        settingsAttachment.name = "reader-customization-settings"
        settingsAttachment.lifetime = .keepAlways
        add(settingsAttachment)

        app.buttons["完成"].tap()
        let closeReader = app.buttons["关闭阅读器"]
        XCTAssertTrue(closeReader.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(closeReader, in: app)
        XCTAssertTrue(app.navigationBars["书架"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.buttons["批量整理书籍"].exists)

        let managementMenus = app.buttons.matching(
            NSPredicate(format: "label ENDSWITH '的更多操作'")
        )
        XCTAssertGreaterThan(managementMenus.count, 0)
        managementMenus.firstMatch.tap()
        XCTAssertTrue(app.buttons["编辑书籍信息"].waitForExistence(timeout: 3))
        app.buttons["编辑书籍信息"].tap()

        XCTAssertTrue(app.navigationBars["管理书籍"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["更换封面"].exists)
        XCTAssertTrue(app.textFields["书名"].exists)
        XCTAssertTrue(app.textFields["作者"].exists)
        XCTAssertTrue(app.textFields["系列（可选）"].exists)
        XCTAssertTrue(app.textFields["文件夹，例如：日本文学"].exists)

        let managementAttachment = XCTAttachment(screenshot: app.screenshot())
        managementAttachment.name = "book-metadata-management"
        managementAttachment.lifetime = .keepAlways
        add(managementAttachment)
        app.terminate()
    }

    func testRealBookTranslationDoesNotExposeTemporarySpeechControls() throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=horizontal",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        let rubyTarget = app.otherElements["selection-ruby-target"]
        XCTAssertTrue(rubyTarget.waitForExistence(timeout: 35))
        let expectedBaseText = try XCTUnwrap(rubyTarget.value as? String)
        let visibleBase = app.staticTexts[expectedBaseText].firstMatch
        XCTAssertTrue(visibleBase.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(visibleBase, in: app)

        XCTAssertFalse(
            app.buttons["朗读当前句段"].waitForExistence(timeout: 3)
        )
        XCTAssertFalse(app.buttons["停止朗读"].exists)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "reader-speech-controls-temporarily-removed"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    func testLongParagraphTranslationUsesBoundedScrollableFloatingWindow()
        throws
    {
        try verifyTranslationFloatingWindow(mode: "paragraph")
    }

    func testVerticalTranslationFloatsToTheLeftOrRightOfOriginalText() throws {
        try verifyTranslationFloatingWindow(mode: "vertical")
    }

    private func verifyTranslationFloatingWindow(mode: String) throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=\(mode == "vertical" ? "publication" : "horizontal")",
            "--jerreader-translation-overlay-ui-test=\(mode)",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        let original = app.descendants(matching: .any)
            .matching(identifier: "translation-source-diagnostic")
            .firstMatch
        let floatingWindow = app.descendants(matching: .any)
            .matching(identifier: "translation-card-diagnostic")
            .firstMatch
        XCTAssertTrue(original.waitForExistence(timeout: 60))
        XCTAssertTrue(floatingWindow.waitForExistence(timeout: 30))

        let originalFrame = original.frame
        let windowFrame = floatingWindow.frame
        XCTAssertFalse(originalFrame.isEmpty)
        XCTAssertFalse(windowFrame.isEmpty)
        XCTAssertGreaterThanOrEqual(windowFrame.height, 285)
        XCTAssertLessThanOrEqual(windowFrame.height, 306)
        XCTAssertFalse(
            windowFrame.intersects(originalFrame),
            "The translation window covers the original text: window=\(windowFrame) original=\(originalFrame)"
        )

        if mode == "vertical" {
            let isLeft = windowFrame.maxX <= originalFrame.minX + 1
            let isRight = windowFrame.minX >= originalFrame.maxX - 1
            XCTAssertTrue(
                isLeft || isRight,
                "The vertical translation window is not beside the original column"
            )
            XCTAssertGreaterThanOrEqual(windowFrame.width, 143)
        }

        let start = app.coordinate(withNormalizedOffset: .zero).withOffset(
            CGVector(dx: windowFrame.midX, dy: windowFrame.midY + 70)
        )
        let end = app.coordinate(withNormalizedOffset: .zero).withOffset(
            CGVector(dx: windowFrame.midX, dy: windowFrame.midY - 70)
        )
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(floatingWindow.exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "translation-floating-window-\(mode)"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    /// Readium's accessibility scroll container can incorrectly claim nearby
    /// SwiftUI controls or text as descendants that need scrolling. Tapping an
    /// already-visible screen coordinate avoids a spurious AX scroll action
    /// without weakening the existence and geometry assertions.
    private func tapVisibleControlByCoordinate(
        _ element: XCUIElement,
        in app: XCUIApplication
    ) {
        let frame = element.frame
        XCTAssertFalse(frame.isEmpty)
        guard !frame.isEmpty else { return }
        app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: frame.midX, dy: frame.midY))
            .tap()
    }

    private func openReaderSettings(in app: XCUIApplication) {
        let moreActions = app.buttons["更多阅读操作"]
        XCTAssertTrue(moreActions.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(moreActions, in: app)
        let readerSettings = app.buttons["阅读设置"]
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 5))
        readerSettings.tap()
        XCTAssertTrue(
            app.navigationBars["阅读与翻译"].waitForExistence(timeout: 5)
        )
    }

    private func waitForLayout(
        _ diagnostic: XCUIElement,
        prefix: String
    ) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(
                format: "value BEGINSWITH %@",
                prefix
            ),
            object: diagnostic
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [expectation], timeout: 12),
            .completed,
            "Unexpected layout state: \(diagnostic.value ?? "nil")"
        )
    }

    private func verifyPhysicalRubyLongPress(
        orientation: String,
        forcesNativeCallbackMiss: Bool = false
    ) throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }
        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=\(orientation)",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        if forcesNativeCallbackMiss {
            app.launchArguments.append("--jerreader-selection-force-native-callback-miss")
        }
        app.launch()

        let rubyTarget = app.otherElements["selection-ruby-target"]
        XCTAssertTrue(
            rubyTarget.waitForExistence(timeout: 35),
            "The real EPUB did not expose a visible ruby target"
        )
        let expectedBaseText = try XCTUnwrap(rubyTarget.value as? String)
        XCTAssertFalse(expectedBaseText.isEmpty)

        // Readium may finish a vertical-writing reflow after the diagnostic
        // readiness target is published. Query the live WebKit accessibility
        // node immediately before the gesture instead of pressing the target's
        // potentially stale frame. This keeps the test on the visible kanji in
        // both horizontal and publication layouts.
        let visibleBase = app.staticTexts[expectedBaseText].firstMatch
        XCTAssertTrue(
            visibleBase.waitForExistence(timeout: 5),
            "The expected base kanji is not visible in the current Readium page"
        )
        let targetFrame = visibleBase.frame
        XCTAssertFalse(targetFrame.isEmpty)

        // Press the current base-glyph coordinate in the WKWebView.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: targetFrame.midX, dy: targetFrame.midY))
            .press(forDuration: 0.85)

        // Assert our app-owned overlay first. Waiting for the simulator-only
        // Apple Translation sheet would hide the app's accessibility tree and
        // turn a visually correct selection into a false-negative test.
        let baseHighlight = app.otherElements["epub-base-selection-highlight"]
        let didExposeBaseHighlight = baseHighlight.waitForExistence(timeout: 2)
        let diagnostic = app.otherElements["selection-diagnostic-status"]
        let diagnosticValue = diagnostic.exists
            ? (diagnostic.value as? String ?? "missing value")
            : "missing diagnostic element"

        // UI automation can subsequently open Apple's simulator-only
        // Translation sheet and hide the app's accessibility tree. The
        // existence result above was captured before that sheet appears.
        XCTAssertTrue(
            didExposeBaseHighlight,
            "A physical long press did not produce the independent base-text highlight: "
                + diagnosticValue
        )
        let baseHighlightFrame = baseHighlight.frame
        XCTAssertFalse(baseHighlightFrame.isEmpty)
        XCTAssertEqual(baseHighlight.value as? String, expectedBaseText)
        XCTAssertTrue(
            baseHighlightFrame.insetBy(dx: -2, dy: -2).intersects(targetFrame),
            "The base-text highlight is not aligned with the pressed kanji. "
                + "highlight=\(baseHighlightFrame) target=\(targetFrame)"
        )

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = forcesNativeCallbackMiss
            ? "physical-ruby-long-press-fallback-\(orientation)"
            : "physical-ruby-long-press-\(orientation)"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    /// Scrolls the form until [element] is on screen and tappable.
    ///
    /// A plain `waitForExistence` is not enough on a long SwiftUI `Form`: rows
    /// below the fold are not realised at all, so the query has nothing to
    /// match until the scroll brings them into range.
    /// Searches downwards first, then back up: turning a toggle on inserts a
    /// colour picker above the control being looked for, so a one-way scroll
    /// can walk straight past it.
    private func scrollUntilHittable(
        _ element: XCUIElement,
        in app: XCUIApplication,
        attempts: Int = 12
    ) -> Bool {
        for step in 0 ..< (attempts * 2) {
            if element.exists, element.isHittable {
                // A swipe leaves the form coasting. Tapping the frame that was
                // read mid-deceleration lands on whatever has slid into that
                // spot by then, which is how a tap on the save control ended up
                // scrolling the page instead.
                Thread.sleep(forTimeInterval: 0.5)
                if element.exists, element.isHittable { return true }
                continue
            }
            if step < attempts {
                app.swipeUp()
            } else {
                app.swipeDown()
            }
            Thread.sleep(forTimeInterval: 0.4)
        }
        return element.exists && element.isHittable
    }

    /// Puts a `Form` row's toggle into a known state.
    ///
    /// The row and the switch share an accessibility label, and the query
    /// matches the row. Tapping a row's centre lands on its text, which a
    /// SwiftUI `Toggle` ignores — so aim at the trailing edge where the switch
    /// itself is. Does nothing when the toggle already reads [on].
    private func setToggle(
        _ toggleRow: XCUIElement,
        on: Bool,
        in app: XCUIApplication
    ) {
        guard scrollUntilHittable(toggleRow, in: app) else { return }
        guard ((toggleRow.value as? String) == "1") != on else { return }
        toggleRow.coordinate(withNormalizedOffset: CGVector(dx: 0.92, dy: 0.5)).tap()
    }

    /// Saving a background + selection pair as a colour set, from the screen a
    /// reader would actually use.
    ///
    /// The store itself is covered by `ReaderColorPresetStoreTest` in `:core`.
    /// What only a driven UI can show is that the save control is reachable and
    /// works from the state the page is *actually* in, and that a second set
    /// joins the first rather than replacing it — 「保存配色套系，输入文字的时候
    /// 有问题，并且为什么只能保存一个？」. Requiring both custom toggles before the
    /// save would enable is what made it look like only one could ever be kept:
    /// the pair is now read off whatever the page is showing, built-in theme
    /// included.
    func testColorPresetCanBeSavedFromGlobalReaderDefaults() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        // No UserDefaults overrides here on purpose: an argument-domain value
        // wins over anything the app writes, so seeding these keys would stop
        // the very toggles and saves this test is driving from taking effect.
        // The test normalises the state it depends on through the UI instead.
        app.launch()

        let settingsTab = app.tabBars.buttons["设置"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 20), app.debugDescription)
        settingsTab.tap()

        // The row is a NavigationLink around a composed label, so its own
        // accessibility label is the title plus the detail line. Match the
        // title text inside it rather than guessing at the combination.
        let defaultsRow = app.staticTexts["默认阅读排版"].firstMatch
        XCTAssertTrue(defaultsRow.waitForExistence(timeout: 15), app.debugDescription)
        defaultsRow.tap()

        // The 背景 section is far below the fold, and a SwiftUI Form only
        // realises the rows it has scrolled near.
        let backgroundToggle = app.switches["自定义背景颜色"].firstMatch
        XCTAssertTrue(
            scrollUntilHittable(backgroundToggle, in: app),
            "Never reached the 背景 section: " + app.debugDescription
        )

        let selectionToggle = app.switches["自定义选区颜色"].firstMatch
        XCTAssertTrue(scrollUntilHittable(selectionToggle, in: app), app.debugDescription)

        // Clear whatever a previous run left behind. Neither custom colour is
        // on now, which used to be the state in which nothing could be saved.
        setToggle(backgroundToggle, on: false, in: app)
        setToggle(selectionToggle, on: false, in: app)

        let saveButton = app.buttons["保存当前配色为套系"].firstMatch
        XCTAssertTrue(scrollUntilHittable(saveButton, in: app), app.debugDescription)
        XCTAssertTrue(
            saveButton.isEnabled,
            "A built-in theme is a complete pair of colours and must be savable"
        )

        // Fresh names each run, so the assertions cannot pass on sets left over
        // from an earlier one.
        let stamp = Int(Date().timeIntervalSince1970) % 10_000
        let themePresetName = "原色\(stamp)"
        let customPresetName = "夜读\(stamp)"

        saveSet(named: themePresetName, using: saveButton, in: app)

        // The second set is saved off a *different* background, so it is a
        // genuinely new set rather than the same one renamed — the swatch row
        // has to end up holding both.
        setToggle(backgroundToggle, on: true, in: app)
        XCTAssertTrue(scrollUntilHittable(saveButton, in: app), app.debugDescription)
        saveSet(named: customPresetName, using: saveButton, in: app)

        // The row sits directly above the save control, so scroll to that and
        // let the row come with it — hunting the swatch by swiping walks past
        // a 38pt circle easily.
        XCTAssertTrue(scrollUntilHittable(saveButton, in: app), app.debugDescription)

        for name in [themePresetName, customPresetName] {
            // The swatch merges its children into one accessibility element, so
            // the name may be reachable either through that combined label or
            // as the caption underneath it, depending on how SwiftUI groups the
            // cell.
            let swatch = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label == %@", name + "配色套系"))
                .firstMatch
            let caption = app.staticTexts[name].firstMatch
            XCTAssertTrue(
                swatch.waitForExistence(timeout: 5) || caption.exists,
                "\(name) should still be in the swatch row: " + app.debugDescription
            )
        }

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "reader-color-preset-saved"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }

    /// Drives the naming sheet through one save.
    ///
    /// The typing is part of what is under test: the field used to sit under
    /// the keyboard in a fixed-height detent, and the text is no longer
    /// rewritten on every keystroke, so what was typed must come back verbatim.
    private func saveSet(
        named name: String,
        using saveButton: XCUIElement,
        in app: XCUIApplication
    ) {
        saveButton.tap()

        // Naming happens in its own sheet, so nothing here has to be scrolled
        // to and the keyboard has nothing to cover.
        XCTAssertTrue(
            app.navigationBars["保存配色套系"].waitForExistence(timeout: 6),
            app.debugDescription
        )

        let nameField = app.textFields.firstMatch
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText(name)
        XCTAssertEqual(nameField.value as? String, name)
        app.buttons["保存"].firstMatch.tap()
    }

    /// 「我希望可以在 APP 的『界面主题』那块单独调节，是白天、黑夜还是跟随系统」.
    ///
    /// The decision itself is `JerreaderAppearanceMode` in `:core`, tested
    /// there. What only a driven UI shows is that the three choices are on the
    /// 界面主题 page the user named, and that picking one sticks.
    func testInterfaceThemeOffersLightDarkAndSystemAppearance() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        app.launch()

        let settingsTab = app.tabBars.buttons["设置"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 20), app.debugDescription)
        settingsTab.tap()

        let themeRow = app.staticTexts["界面主题"].firstMatch
        XCTAssertTrue(themeRow.waitForExistence(timeout: 15), app.debugDescription)
        themeRow.tap()

        for title in ["跟随系统", "白天", "黑夜"] {
            XCTAssertTrue(
                app.staticTexts[title].firstMatch.waitForExistence(timeout: 8),
                "\(title) should be offered on 界面主题: " + app.debugDescription
            )
        }

        // The accent rows are still on the same page — the mode is an addition
        // to 界面主题, not a replacement for the colour scheme.
        XCTAssertTrue(
            app.staticTexts["主题色系"].firstMatch.waitForExistence(timeout: 8),
            app.debugDescription
        )

        let dark = app.staticTexts["黑夜"].firstMatch
        dark.tap()
        // The row is a button wrapping a composed label; the selected trait is
        // set on the button, so ask the ancestor rather than the text.
        let darkRow = app.buttons.containing(
            NSPredicate(format: "label CONTAINS %@", "黑夜")
        ).firstMatch
        XCTAssertTrue(darkRow.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(darkRow.isSelected, app.debugDescription)

        // Leave the simulator on the default so a later run of any other test
        // does not start in a scheme it did not choose.
        app.staticTexts["跟随系统"].firstMatch.tap()

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "app-appearance-mode"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.terminate()
    }
}
