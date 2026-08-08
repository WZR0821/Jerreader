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

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "standalone-translator-redesign"
        attachment.lifetime = .keepAlways
        add(attachment)
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
}
