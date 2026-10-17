import UIKit
import XCTest

/// Uses an in-memory SwiftData store. No book or learning data is overwritten.
@MainActor
final class JerreaderLearningUITests: XCTestCase {
    private let tools = ["生词复习", "原句练习卡", "语法知识库", "阅读成长报告"]

    private func launch(empty: Bool = false, large: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["--jerreader-learning-ui-fixture", "--jerreader-standalone-translator-ui-test",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-ApplePersistenceIgnoreState", "YES"]
        if empty { app.launchArguments += ["--jerreader-learning-ui-empty"] }
        if large { app.launchArguments += ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityM", "--jerreader-learning-ui-dark"] }
        app.launch()
        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 20))
        if large { assertDarkAppearance(app) }
        return app
    }

    private func assertDarkAppearance(_ app: XCUIApplication) {
        guard let image = app.screenshot().image.cgImage,
              let margin = image.cropping(to: CGRect(
                x: (5 * CGFloat(image.width) / app.windows.firstMatch.frame.width).rounded(.down),
                y: CGFloat(image.height / 2), width: 1, height: 1
              )) else { XCTFail("Missing rendered appearance sample"); return }
        var pixel = [UInt8](repeating: 0, count: 4)
        let rendered = pixel.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1,
                bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            context.draw(margin, in: CGRect(x: 0, y: 0, width: 1, height: 1))
            return true
        }
        XCTAssertTrue(rendered)
        XCTAssertGreaterThan(pixel[3], 200)
        XCTAssertLessThan(pixel.prefix(3).max() ?? 255, 80, "Dark mode must be present in the rendered canvas")
    }

    private func open(_ title: String, app: XCUIApplication) {
        app.buttons["练习与工具"].tap()
        let action = title == "生词复习" ? "开始复习" : title
        let item = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", action)).firstMatch
        for _ in 0..<6 {
            if item.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(item.waitForExistence(timeout: 5)); item.tap()
        XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
    }

    private func capture(_ name: String, app: XCUIApplication) {
        // A SwiftUI transition can still be rendering after its new AX nodes
        // exist. Preserve a settled frame rather than a blend of two pages.
        var screenshot = app.screenshot()
        var previousPixels = screenshot.pngRepresentation
        var stableFrames = 0
        let deadline = Date().addingTimeInterval(5)
        while stableFrames < 2 && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.2)
            screenshot = app.screenshot()
            let pixels = screenshot.pngRepresentation
            stableFrames = pixels == previousPixels ? stableFrames + 1 : 0
            previousPixels = pixels
        }
        XCTAssertEqual(stableFrames, 2, "The page must settle before its visual reference is captured")
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }

    private func showPractice(_ app: XCUIApplication) {
        let practice = app.buttons["练习与工具"]
        practice.tap()
        XCTAssertTrue(app.staticTexts["今日练习"].waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: practice)
        waitForExpectations(timeout: 5)
    }

    func testJapaneseLearningWorkflow() {
        let app = launch()
        XCTAssertFalse(app.buttons["日语 → 中文"].exists)
        XCTAssertFalse(app.buttons["中文 → 日语"].exists)
        XCTAssertFalse(app.navigationBars["学习"].buttons["练习与工具"].exists)
        let vocabularyX = app.buttons["生词本"].frame.midX
        let practiceX = app.buttons["练习与工具"].frame.midX
        XCTAssertGreaterThan(practiceX, vocabularyX)
        XCTAssertLessThan(practiceX, app.buttons["历史"].frame.midX)
        capture("ios-learning-hub", app: app)
        app.buttons["生词本"].tap()
        XCTAssertTrue(app.staticTexts["食べる"].waitForExistence(timeout: 5))
        capture("ios-vocabulary", app: app)
        let savedWord = app.staticTexts["食べる"].firstMatch
        XCTAssertTrue(savedWord.waitForExistence(timeout: 5)); savedWord.tap()
        XCTAssertTrue(app.navigationBars["词条详情"].waitForExistence(timeout: 5))
        capture("ios-word-detail", app: app)
        app.buttons["复制"].tap()
        XCTAssertTrue(app.alerts["已复制"].waitForExistence(timeout: 5)); app.alerts.buttons["好"].tap()
        app.buttons["已收藏"].tap()
        XCTAssertTrue(app.buttons["收藏"].waitForExistence(timeout: 5)); app.buttons["收藏"].tap()
        XCTAssertTrue(app.buttons["已收藏"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        app.buttons["历史"].tap()
        XCTAssertTrue(app.staticTexts["食べる"].waitForExistence(timeout: 5))
        capture("ios-history", app: app)
        app.buttons["翻译"].tap()
        app.tabBars.buttons["书架"].tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "所有书籍")).firstMatch.waitForExistence(timeout: 5))
        capture("ios-library-populated", app: app)
        let bookMenu = app.buttons["中日阅读练习的更多操作"].firstMatch
        XCTAssertTrue(bookMenu.waitForExistence(timeout: 5))
        bookMenu.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["编辑书籍信息"].waitForExistence(timeout: 5))
        app.buttons["编辑书籍信息"].tap()
        XCTAssertTrue(app.navigationBars["管理书籍"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["更换封面"].exists)
        capture("ios-book-editor", app: app)
        app.buttons["取消"].tap(); app.tabBars.buttons["学习"].tap()
        app.buttons["练习与工具"].tap()
        XCTAssertTrue(app.staticTexts["今日练习"].waitForExistence(timeout: 5))
        capture("ios-practice", app: app)
        app.buttons["开始复习"].tap()
        XCTAssertTrue(app.navigationBars[tools[0]].waitForExistence(timeout: 5))
        app.buttons["开始复习"].tap()
        app.buttons["显示释义"].tap()
        XCTAssertTrue(app.staticTexts["たべる"].exists)
        capture("ios-review-answer", app: app)
        app.buttons["认识"].tap()
        XCTAssertTrue(app.staticTexts["本组复习完成"].exists)
        app.navigationBars.buttons.firstMatch.tap()
        open(tools[1], app: app)
        capture("ios-sentences", app: app)
        app.buttons.containing(.staticText, identifier: "毎日、日本語の本を読む。").firstMatch.tap()
        app.buttons["显示答案与译文"].tap()
        XCTAssertTrue(app.staticTexts["每天读日语书。"].exists)
        capture("ios-sentence-answer", app: app)
        app.navigationBars.buttons.firstMatch.tap()
        app.navigationBars.buttons.firstMatch.tap()
        open(tools[2], app: app)
        capture("ios-grammar", app: app)
        app.buttons["编辑"].tap()
        XCTAssertTrue(app.navigationBars["保存语法"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["音楽を聞きながら、本を読む。"].exists)
        capture("ios-grammar-editor", app: app)
        app.buttons["取消"].tap()
        app.buttons["删除"].tap()
        XCTAssertTrue(app.alerts["删除这条语法收藏？"].waitForExistence(timeout: 5))
        app.alerts.buttons["取消"].tap()
        XCTAssertTrue(app.staticTexts["〜ながら：同时进行的动作"].exists)
        app.navigationBars.buttons.firstMatch.tap()
        open(tools[3], app: app)
        capture("ios-growth", app: app)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertFalse(app.buttons["离线学习包"].exists)
        app.terminate()
    }

    func testEmptyLearningPages() {
        let app = launch(empty: true)
        showPractice(app)
        capture("ios-empty-practice", app: app)
        app.buttons["生词本"].tap(); capture("ios-empty-vocabulary", app: app)
        app.buttons["历史"].tap(); capture("ios-empty-history", app: app)
        for (index, title) in tools.enumerated() {
            open(title, app: app)
            capture("ios-empty-\(index)", app: app)
            app.navigationBars.buttons.firstMatch.tap()
        }
        app.terminate()
    }

    func testSettingsSecondaryPages() {
        let app = launch(empty: true)
        let draftInput = app.descendants(matching: .any).matching(identifier: "standalone-translation-input").firstMatch
        XCTAssertTrue(draftInput.waitForExistence(timeout: 5))
        draftInput.tap(); draftInput.typeText("practice draft")
        app.buttons["生词本"].tap(); app.buttons["翻译"].tap()
        XCTAssertEqual(draftInput.value as? String, "practice draft")
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.keyboards.firstMatch)
        waitForExpectations(timeout: 5)
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        capture("ios-settings-root", app: app)
        let routes = [("界面主题", "界面主题", "theme"), ("默认阅读排版", "默认阅读排版", "reader"),
                      ("翻译与 AI", "翻译与 AI", "translation"), ("操作指南", "操作指南", "guide"),
                      ("备份与恢复", "备份中心", "backup"), ("数据与隐私", "数据与隐私", "privacy")]
        for (label, title, name) in routes {
            let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", label)).firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 5)); row.tap()
            XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 5))
            XCTAssertTrue(app.tabBars.buttons["学习"].exists)
            capture("ios-settings-" + name, app: app)
            if name == "reader" {
                for _ in 0..<5 {
                    if app.staticTexts["日文原版排版"].isHittable { break }
                    app.swipeUp()
                }
                capture("ios-settings-reader-lower", app: app)
            }
            app.navigationBars.buttons.firstMatch.tap()
            XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        }
        app.tabBars.buttons["学习"].tap()
        XCTAssertTrue(draftInput.waitForExistence(timeout: 5))
        XCTAssertEqual(draftInput.value as? String, "practice draft")
        capture("ios-draft-restored", app: app)
        app.terminate()
    }

    func testOfflineResourcesHaveOneSettingsEntry() {
        let app = launch(empty: true)
        app.tabBars.buttons["设置"].tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "翻译与 AI")).firstMatch.tap()
        let resources = app.buttons["离线资源"].firstMatch
        for _ in 0..<12 { if resources.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(resources.waitForExistence(timeout: 5)); resources.tap()
        XCTAssertTrue(app.staticTexts["英语扩展词典"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["下载"].exists)
        XCTAssertTrue(app.buttons["卸载"].exists)
        XCTAssertFalse(app.buttons["查看完整许可（离线可用）"].exists)
        XCTAssertFalse(app.buttons["查词"].exists)
        XCTAssertFalse(app.textFields["输入日语、假名或中文词语"].exists)
        capture("ios-offline-resources", app: app)
        app.terminate()
    }

    func testLargeTextDarkLearningPages() {
        let app = launch(large: true)
        showPractice(app)
        capture("ios-large-dark-practice", app: app)
        app.buttons["生词本"].tap(); capture("ios-large-dark-vocabulary", app: app)
        app.buttons["历史"].tap(); capture("ios-large-dark-history", app: app)
        for (index, title) in tools.enumerated() {
            open(title, app: app)
            capture("ios-large-dark-\(index)", app: app)
            app.navigationBars.buttons.firstMatch.tap()
        }
        app.terminate()
    }
}

@MainActor
final class JerreaderSelectionUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testStandaloneTranslatorShowsDirectionModeAndProviderControls() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-translation.sourceLanguageChoice", "automatic",
            "-translation.targetLanguage", "zh-Hans",
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
            "--jerreader-learning-ui-fixture", "--jerreader-learning-ui-empty",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-AppleInterfaceStyle", "Light", "-ApplePersistenceIgnoreState", "YES"
        ]
        app.launch()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15)
        )

        let window = app.windows.firstMatch
        XCTAssertTrue(window.exists)
        XCTAssertEqual(window.frame.minX, 0, accuracy: 1)
        XCTAssertEqual(window.frame.minY, 0, accuracy: 1)

        let libraryAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        libraryAttachment.name = "iphone-library-refined-ui"
        libraryAttachment.lifetime = .keepAlways
        add(libraryAttachment)

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

        let author = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Jerry Wong")
        ).firstMatch
        for _ in 0..<4 {
            if author.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(author.waitForExistence(timeout: 5))
        XCTAssertTrue(author.isHittable)
        let aboutAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        aboutAttachment.name = "iphone-about-refined-ui"
        aboutAttachment.lifetime = .keepAlways
        add(aboutAttachment)

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
        XCTAssertTrue(app.buttons["立即备份"].exists)
        XCTAssertFalse(app.staticTexts["自动策略"].exists)
        app.buttons["更多备份选项"].tap()
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
        ] {
            let element = app.staticTexts[label]
            scrollToElement(element, in: app)
            XCTAssertTrue(element.waitForExistence(timeout: 5))
        }
        let managedHeader = app.staticTexts.matching(NSPredicate(format: "label IN %@",
            ["当前文件夹中的备份", "本机旧备份（可导出）"])).firstMatch
        scrollToElement(managedHeader, in: app)
        XCTAssertTrue(managedHeader.waitForExistence(timeout: 5))

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
        app.launchArguments = ["--jerreader-learning-ui-fixture", "--jerreader-learning-ui-empty",
            "--jerreader-standalone-translator-ui-test", "-AppleLanguages", "(zh-Hans)",
            "-AppleLocale", "zh_CN", "-ApplePersistenceIgnoreState", "YES"]
        app.launch()
        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 20))
        app.tabBars.buttons["书架"].tap()

        XCTAssertTrue(
            app.navigationBars["书架"].waitForExistence(timeout: 15)
        )
        let restore = app.buttons["library-restore-backup"]
        XCTAssertTrue(restore.waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.staticTexts["装过 Jerreader 的话，选择以前的备份文件夹即可恢复书架、进度和备份计划。"]
                .exists
        )

        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "iphone-empty-library-restore-entry"
        attachment.lifetime = .keepAlways
        add(attachment)

        restore.tap()
        let cancel = app.buttons.matching(NSPredicate(format: "label IN %@", ["取消", "Cancel"])).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 15), "The system folder picker must open")
        Thread.sleep(forTimeInterval: 0.5)
        let pickerAttachment = XCTAttachment(screenshot: app.screenshot())
        pickerAttachment.name = "ios-backup-folder-picker"
        pickerAttachment.lifetime = .keepAlways
        add(pickerAttachment)
        cancel.tap()
        XCTAssertTrue(
            app.navigationBars["备份中心"].waitForExistence(timeout: 8)
        )
        let reopen = app.buttons["从备份文件夹恢复"]
        scrollToElement(reopen, in: app)
        XCTAssertTrue(reopen.isHittable)
        reopen.tap()
        XCTAssertTrue(cancel.waitForExistence(timeout: 10), "Cancelling must not break the next picker session")
        cancel.tap()
        XCTAssertTrue(app.navigationBars["备份中心"].waitForExistence(timeout: 8))

        app.terminate()
    }

    private func scrollToElement(
        _ element: XCUIElement,
        in app: XCUIApplication
    ) {
        for _ in 0..<5 where !element.exists || !element.isHittable {
            app.swipeUp()
        }
        for _ in 0..<10 where !element.exists || !element.isHittable {
            app.swipeDown()
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
        verifyPageSwipe(in: app, diagnostic: diagnostic, rightToLeft: false)

        openReaderSettings(in: app)
        XCTAssertTrue(app.buttons["原版"].waitForExistence(timeout: 5))
        app.buttons["原版"].tap()
        app.buttons["完成"].tap()
        waitForLayout(
            diagnostic,
            prefix: "publication|vertical-rl|rtl|"
        )
        verifyPageSwipe(in: app, diagnostic: diagnostic, rightToLeft: true)

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
        let readerSettings = app.buttons["阅读设置"]
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(readerSettings, in: app)
        XCTAssertTrue(
            app.navigationBars["阅读与翻译"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.buttons["逐页"].exists || app.buttons["滚动"].exists
        )
        let topOpacity = app.sliders["顶部栏透明度"]
        revealReaderSetting(topOpacity, in: app)
        XCTAssertTrue(topOpacity.waitForExistence(timeout: 5))
        topOpacity.adjust(toNormalizedSliderPosition: 0.35)
        let bottomOpacity = app.sliders["底部栏透明度"]
        revealReaderSetting(bottomOpacity, in: app)
        XCTAssertTrue(bottomOpacity.waitForExistence(timeout: 5))
        bottomOpacity.adjust(toNormalizedSliderPosition: 0.75)
        let immersion = app.switches["沉浸阅读（隐藏状态栏）"]
        revealReaderSetting(immersion, in: app)
        XCTAssertTrue(immersion.exists)
        let initialImmersionValue = try XCTUnwrap(immersion.value as? String)
        tapReaderSwitch(immersion, in: app)
        let immersionChanged = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value != %@", initialImmersionValue), object: immersion)
        XCTAssertEqual(XCTWaiter.wait(for: [immersionChanged], timeout: 5), .completed)
        XCTAssertTrue(app.switches["日语注音"].exists)
        let controlsAttachment = XCTAttachment(screenshot: app.screenshot())
        controlsAttachment.name = "reader-interface-controls"
        controlsAttachment.lifetime = .keepAlways
        add(controlsAttachment)
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
        let statusOption = XCTAttachment(screenshot: app.screenshot())
        statusOption.name = "reader-status-bar-option"
        statusOption.lifetime = .keepAlways
        add(statusOption)
        tapVisibleControlByCoordinate(app.buttons["阅读设置"], in: app)
        let restoreImmersion = app.switches["沉浸阅读（隐藏状态栏）"]
        revealReaderSetting(restoreImmersion, in: app)
        XCTAssertTrue(restoreImmersion.waitForExistence(timeout: 5))
        tapReaderSwitch(restoreImmersion, in: app)
        let immersionRestored = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", initialImmersionValue), object: restoreImmersion)
        XCTAssertEqual(XCTWaiter.wait(for: [immersionRestored], timeout: 5), .completed)
        app.buttons["完成"].tap()
        let closeReader = app.buttons["关闭阅读器"]
        XCTAssertTrue(closeReader.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(closeReader, in: app)
        XCTAssertTrue(app.navigationBars["书架"].waitForExistence(timeout: 8))
        let libraryMore = app.buttons["书架更多操作"]
        XCTAssertTrue(libraryMore.waitForExistence(timeout: 5))
        libraryMore.tap()
        XCTAssertTrue(app.buttons["批量整理书籍"].waitForExistence(timeout: 3))
        app.navigationBars["书架"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()

        let managementMenus = app.buttons.matching(
            NSPredicate(format: "label ENDSWITH '的更多操作'")
        )
        XCTAssertTrue(managementMenus.firstMatch.waitForExistence(timeout: 5))
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

    func testTranslationAvoidanceAcrossEdgesWithFurigana() throws {
        executionTimeAllowance = 180
        for mode in ["paragraph-top", "paragraph-bottom", "vertical-left", "vertical-right"] {
            try verifyTranslationFloatingWindow(mode: mode, showsControlsAndFurigana: true)
        }
    }

    func testActualHorizontalAndVerticalTapTranslationAvoidsOriginalWithFurigana() throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1" else {
            throw XCTSkip("Inject the synthetic EPUB for actual Readium tap geometry")
        }
        for orientation in ["horizontal", "publication"] {
            let app = XCUIApplication()
            app.launchArguments = ["--jerreader-selection-ui-test", "--jerreader-selection-test-href=part0005",
                "--jerreader-selection-orientation=\(orientation)", "--jerreader-offline-translation-ui-test",
                "-ApplePersistenceIgnoreState", "YES"]
            app.launch()
            let target = app.otherElements["selection-ruby-target"]
            XCTAssertTrue(target.waitForExistence(timeout: 40))
            let base = try XCTUnwrap(target.value as? String)
            let text = app.staticTexts[base].firstMatch
            XCTAssertTrue(text.waitForExistence(timeout: 5))
            let tappedTextFrame = text.frame
            tapVisibleControlByCoordinate(text, in: app)
            let result = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Mock 翻译结果")).firstMatch
            XCTAssertTrue(result.waitForExistence(timeout: 15), "Offline Mock must finish through the real tap pipeline")
            let card = app.descendants(matching: .any).matching(identifier: "translation-card-diagnostic").firstMatch
            let original = app.otherElements["translation-source-diagnostic"]
            XCTAssertTrue(card.waitForExistence(timeout: 8)); XCTAssertTrue(original.exists)
            Thread.sleep(forTimeInterval: 0.5)
            XCTAssertTrue(original.frame.contains(CGPoint(x: tappedTextFrame.midX, y: tappedTextFrame.midY)),
                          "The real request rectangle must contain the actual tapped DOM glyph")
            XCTAssertFalse(card.frame.intersects(original.frame), "Actual \(orientation) result covers the selected original")
            XCTAssertFalse(card.frame.intersects(tappedTextFrame), "The real DOM glyph must remain visible")
            let plain = XCTAttachment(screenshot: app.screenshot()); plain.name = "translation-actual-\(orientation)-plain"; plain.lifetime = .keepAlways; add(plain)
            tapVisibleControlByCoordinate(app.buttons["更多翻译操作"], in: app)
            let toggle = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == %@ OR label == %@", "reader-furigana-toggle", "日语注音")).firstMatch
            XCTAssertTrue(toggle.waitForExistence(timeout: 5)); toggle.tap()
            let furigana = app.descendants(matching: .any).matching(NSPredicate(
                format: "identifier == %@ OR label == %@", "japanese-source-furigana", "原文注音")).firstMatch
            XCTAssertTrue(furigana.waitForExistence(timeout: 8))
            Thread.sleep(forTimeInterval: 0.6)
            XCTAssertFalse(card.frame.intersects(original.frame), "Furigana height must be remeasured and avoided")
            XCTAssertFalse(card.frame.intersects(tappedTextFrame), "Furigana must not cover the real DOM glyph")
            XCTAssertGreaterThanOrEqual(card.frame.minX, 0)
            XCTAssertLessThanOrEqual(card.frame.maxX, app.windows.firstMatch.frame.maxX + 1)
            let ruby = XCTAttachment(screenshot: app.screenshot()); ruby.name = "translation-actual-\(orientation)-furigana"; ruby.lifetime = .keepAlways; add(ruby)
            app.terminate()
        }
    }

    private func verifyTranslationFloatingWindow(mode: String, showsControlsAndFurigana: Bool = false) throws {
        guard ProcessInfo.processInfo.environment["JERREADER_RUN_REAL_RUBY_UI_TESTS"] == "1"
        else {
            throw XCTSkip("Inject UITestRubyBook.epub and enable the real-ruby UI test")
        }

        let app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-selection-ui-test",
            "--jerreader-selection-test-href=part0005",
            "--jerreader-selection-orientation=\(mode.hasPrefix("vertical") ? "publication" : "horizontal")",
            "--jerreader-translation-overlay-ui-test=\(mode)",
            "-ApplePersistenceIgnoreState",
            "YES"
        ]
        if showsControlsAndFurigana {
            app.launchArguments += ["--jerreader-selection-controls-visible", "--jerreader-furigana-ui-test",
                "-translation.displayMode", "topBanner"]
        }
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

        if mode.hasPrefix("vertical") {
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

    private func tapReaderSwitch(_ element: XCUIElement, in app: XCUIApplication) {
        let frame = element.frame
        XCTAssertFalse(frame.isEmpty)
        guard !frame.isEmpty else { return }
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: frame.maxX - min(frame.width / 2, 22), dy: frame.midY
        )).tap()
    }

    private func revealReaderSetting(_ element: XCUIElement, in app: XCUIApplication) {
        let viewport = app.windows.firstMatch.frame.insetBy(dx: 12, dy: 110)
        for attempt in 0 ..< 12 {
            if element.exists {
                let frame = element.frame
                if element.isHittable && viewport.contains(
                    CGPoint(x: frame.midX, y: frame.midY)
                ) { return }
                if !frame.isEmpty && frame.midY < viewport.minY {
                    app.swipeDown()
                    continue
                }
            }
            // A reopened Form can retain its previous scroll position. Search
            // both directions when an offscreen lazy row has no frame yet.
            if attempt < 4 { app.swipeUp() } else { app.swipeDown() }
        }
        XCTAssertTrue(element.isHittable, "Reader setting could not be revealed: \(element.label)")
    }

    private func openReaderSettings(in app: XCUIApplication) {
        let readerSettings = app.buttons["阅读设置"]
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 5))
        tapVisibleControlByCoordinate(readerSettings, in: app)
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

    private func verifyPageSwipe(in app: XCUIApplication, diagnostic: XCUIElement, rightToLeft: Bool) {
        func viewport() -> (x: Double, width: Double, maximum: Double, paging: Bool)? {
            guard let value = diagnostic.value as? String else { return nil }
            let fields = Dictionary(uniqueKeysWithValues: value.split(separator: "|").compactMap { field -> (String, String)? in
                let parts = field.split(separator: "=", maxSplits: 1)
                guard parts.count == 2 else { return nil }
                return (String(parts[0]), String(parts[1]))
            })
            guard let x = fields["x"].flatMap(Double.init),
                  let width = fields["w"].flatMap(Double.init), width > 1,
                  let maximum = fields["max"].flatMap(Double.init)
            else { return nil }
            return (x, width, maximum, fields["paging"] == "true")
        }
        guard let before = viewport() else { XCTFail("Missing live viewport diagnostics"); return }
        XCTAssertTrue(before.paging)
        let frame = app.windows.firstMatch.frame
        let start = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: frame.width * (rightToLeft ? 0.22 : 0.78), dy: frame.height * 0.52))
        let end = app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(
            dx: frame.width * (rightToLeft ? 0.78 : 0.22), dy: frame.height * 0.52))
        start.press(forDuration: 0.05, thenDragTo: end)
        var settled = false
        var stable = 0
        var previous: Double?
        for _ in 0..<40 {
            Thread.sleep(forTimeInterval: 0.25)
            guard let after = viewport(), after.paging else { continue }
            let moved = rightToLeft ? before.x - after.x : after.x - before.x
            let distance = rightToLeft ? after.maximum - after.x : after.x
            let aligned = abs(distance - (distance / after.width).rounded() * after.width) < 0.6
                || abs(after.x) < 0.6 || abs(after.x - after.maximum) < 0.6
            if moved > after.width * 0.5 && aligned {
                stable = previous.map { abs($0 - after.x) < 0.6 } == true ? stable + 1 : 1
                previous = after.x
                if stable >= 2 { settled = true; break }
            }
        }
        XCTAssertTrue(settled, "A physical swipe must settle on a page: \(diagnostic.value ?? "nil")")
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = rightToLeft ? "reader-vertical-swipe-pinned" : "reader-horizontal-swipe-pinned"
        attachment.lifetime = .keepAlways
        add(attachment)
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
