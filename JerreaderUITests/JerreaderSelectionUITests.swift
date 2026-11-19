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
            if name == "theme" { assertThemePage(app) }
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
        // 2.0.1: 设置 opens on its home page again after the reader has been
        // somewhere else. It used to keep whichever sub-page was open last.
        let privacy = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "数据与隐私")).firstMatch
        XCTAssertTrue(privacy.waitForExistence(timeout: 5)); privacy.tap()
        XCTAssertTrue(app.navigationBars["数据与隐私"].waitForExistence(timeout: 5))
        app.tabBars.buttons["学习"].tap()
        XCTAssertTrue(draftInput.waitForExistence(timeout: 5))
        XCTAssertEqual(draftInput.value as? String, "practice draft")
        capture("ios-draft-restored", app: app)
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["数据与隐私"].exists)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "界面主题")).firstMatch.exists)
        capture("ios-settings-reopened", app: app)
        app.terminate()
    }

    /// 界面主题 (2.0.1): the preview, 外观模式, eight colours, 背景光晕 and the
    /// way to the reader's paper. Choosing a colour moves the selection and
    /// the summary under the swatches.
    private func assertThemePage(_ app: XCUIApplication) {
        let anything = app.descendants(matching: .any)
        XCTAssertTrue(anything.matching(identifier: "themePreview").firstMatch.waitForExistence(timeout: 5))
        for mode in ["system", "light", "dark"] {
            XCTAssertTrue(app.buttons["appearanceMode-" + mode].exists, "Missing 外观模式 option \(mode)")
        }
        XCTAssertTrue(app.buttons["appearanceMode-system"].isSelected)
        let colours = ["ocean", "forest", "wisteria", "amber", "berry", "teal", "terracotta", "graphite"]
        for colour in colours {
            XCTAssertTrue(app.buttons["themeSwatch-" + colour].exists, "Missing theme colour \(colour)")
        }
        let ocean = app.buttons["themeSwatch-ocean"]
        let graphite = app.buttons["themeSwatch-graphite"]
        // A run that stopped half way may have left another colour chosen.
        if !ocean.isSelected {
            ocean.tap()
            expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: ocean)
            waitForExpectations(timeout: 5)
        }
        graphite.tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: graphite)
        waitForExpectations(timeout: 5)
        XCTAssertFalse(ocean.isSelected)
        let summary = anything.matching(identifier: "themeSelectedSummary").firstMatch
        XCTAssertTrue(summary.exists)
        XCTAssertTrue(summary.label.contains("石墨"), "The summary still reads: \(summary.label)")
        capture("ios-settings-theme-graphite", app: app)
        ocean.tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: ocean)
        waitForExpectations(timeout: 5)

        let glow = app.switches["themeBackdropGlow"]
        let paper = anything.matching(identifier: "themeReaderPaperLink").firstMatch
        for _ in 0..<5 {
            if glow.exists && glow.isHittable && paper.exists && paper.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(glow.waitForExistence(timeout: 5))
        XCTAssertEqual(glow.value as? String, "1")
        XCTAssertTrue(paper.exists)
        capture("ios-settings-theme-lower", app: app)
        // 阅读纸张 opens 默认阅读排版 and comes back here, not to the home page.
        paper.tap()
        XCTAssertTrue(app.navigationBars["默认阅读排版"].waitForExistence(timeout: 5))
        let margins = app.sliders["readerDefaultsVerticalMargins"]
        for _ in 0..<6 {
            if margins.exists && margins.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(margins.exists, "默认阅读排版 must offer 上下边距")
        capture("ios-settings-reader-margins", app: app)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["界面主题"].waitForExistence(timeout: 5))
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

        // "+" first asks for a format. The sheet opens at full height, so the
        // button that opens the system picker shows without scrolling.
        app.buttons["导入电子书"].tap()
        XCTAssertTrue(app.staticTexts["先选择文件格式"].waitForExistence(timeout: 5))
        let chooseDocument = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "选择文档")
        ).firstMatch
        XCTAssertTrue(chooseDocument.waitForExistence(timeout: 5))
        XCTAssertTrue(chooseDocument.isHittable, "选择文档 must show without scrolling")
        Thread.sleep(forTimeInterval: 0.6)
        let importAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        importAttachment.name = "ios-import-options"
        importAttachment.lifetime = .keepAlways
        add(importAttachment)
        app.buttons["只选 PDF 文件"].tap()
        XCTAssertTrue(
            app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "选择 PDF 文件"))
                .firstMatch.waitForExistence(timeout: 5)
        )
        Thread.sleep(forTimeInterval: 0.4)
        let pdfAttachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        pdfAttachment.name = "ios-import-options-pdf"
        pdfAttachment.lifetime = .keepAlways
        add(pdfAttachment)
        app.navigationBars["导入电子书"].buttons["取消"].tap()
        XCTAssertTrue(
            app.staticTexts["先选择文件格式"].waitForNonExistence(timeout: 5),
            "The import sheet did not close"
        )

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
        let immersion = app.switches["隐藏状态栏"]
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
        let restoreImmersion = app.switches["隐藏状态栏"]
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

/// 设置 and 学习 › 翻译 each fill the room between their bars exactly: the
/// content is as tall as the room, nothing moves on a drag, and the last
/// element ends just above the tab bar. Meant for the 440 × 956 iPhone 16 Pro
/// Max, the tallest iPhone: `--jerreader-fixture-shorter` takes height off the
/// bottom of a page to stand in for a shorter phone. The AI model pages are
/// checked on the way. Every sheet is cancelled, so nothing is saved.
@MainActor
final class JerreaderOneScreenUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch(_ extra: [String] = []) {
        app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-learning-ui-fixture", "--jerreader-learning-ui-empty",
            "--jerreader-standalone-translator-ui-test", "--jerreader-one-screen-probe",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN", "-ApplePersistenceIgnoreState", "YES"
        ] + extra
        app.launch()
        XCTAssertTrue(app.navigationBars["学习"].waitForExistence(timeout: 20))
    }

    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func attach(_ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private var translateLast: XCUIElement { element("standalone-translation-provider-help") }
    private var settingsLast: XCUIElement { element("appVersionLabel") }

    /// What the page's scroll view reports about itself in test builds.
    private func reading(_ page: String) -> [String: CGFloat] {
        let probe = element(page + "Probe")
        XCTAssertTrue(probe.waitForExistence(timeout: 5), page)
        var values: [String: CGFloat] = [:]
        for part in probe.label.split(separator: " ") {
            let pair = part.split(separator: "=")
            if pair.count == 2, let number = Double(pair[1]) { values[String(pair[0])] = CGFloat(number) }
        }
        return values
    }

    /// Drags from the middle of `element` by `dy` points. A drag that starts
    /// on a label moves the page itself, not an input or a result that
    /// scrolls on its own.
    private func drag(from element: XCUIElement, by dy: CGFloat) {
        let start = element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: dy)))
    }

    /// The page is one screen: content as tall as its room, still after drags
    /// both ways, and `last` ending `gap` points above the tab bar.
    private func assertOneScreen(_ page: String, last: XCUIElement, gap: ClosedRange<CGFloat>,
                                 shorter: CGFloat = 0, _ name: String) {
        XCTAssertTrue(last.waitForExistence(timeout: 5), name)
        let before = last.frame
        for _ in 0..<2 {
            drag(from: last, by: -260)
            drag(from: last, by: 60)
        }
        let values = reading(page)
        let space = app.tabBars.firstMatch.frame.minY - shorter - last.frame.maxY
        print("ONESCREEN \(name) \(values) space=\(space) last=\(last.frame) window=\(app.windows.firstMatch.frame)")
        XCTAssertEqual(values["content"] ?? -1, values["room"] ?? -2, accuracy: 0.5, "\(name): \(values)")
        XCTAssertEqual(values["moved"] ?? -1, 0, accuracy: 0.5, "\(name) moved: \(values)")
        XCTAssertEqual(last.frame.minY, before.minY, accuracy: 0.5, name)
        XCTAssertTrue(gap.contains(space), "\(name): \(space) points under the last element")
    }

    private func showSettings() {
        app.tabBars.buttons["设置"].tap()
        XCTAssertTrue(app.navigationBars["设置"].waitForExistence(timeout: 5))
    }

    func testBothMainScreensFillOneScreen() {
        launch()
        assertOneScreen("standalone-translation-page", last: translateLast, gap: 12...24, "翻译")
        attach("one-screen-translate")
        showSettings()
        let title = app.navigationBars["设置"].frame.height
        assertOneScreen("settingsHome", last: settingsLast, gap: 4...16, "设置")
        XCTAssertEqual(app.navigationBars["设置"].frame.height, title, accuracy: 0.5, "The large title stays large")
        attach("one-screen-settings")
        app.terminate()
    }

    func testBothMainScreensInDarkAppearance() {
        launch(["--jerreader-learning-ui-dark"])
        assertOneScreen("standalone-translation-page", last: translateLast, gap: 12...24, "翻译 深色")
        attach("one-screen-translate-dark")
        showSettings()
        assertOneScreen("settingsHome", last: settingsLast, gap: 4...16, "设置 深色")
        attach("one-screen-settings-dark")
        element("aiModelsSettingsLink").tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))
        attach("ai-models-dark")
        app.terminate()
    }

    // On the 16 Pro Max (956 points, 62 under the status bar) taking these
    // off the bottom leaves the room of a 932-point phone with 59 (15 Pro Max,
    // 16 Plus), of the 874-point 16 Pro with 62, and of the 852-point 16 with
    // 59. One phone per test keeps each well inside its time allowance.
    private func assertShorterPhoneGetsOneScreen(_ shorter: Int) {
        launch(["--jerreader-fixture-shorter=\(shorter)"])
        assertOneScreen("standalone-translation-page", last: translateLast, gap: 12...24,
                        shorter: CGFloat(shorter), "翻译 −\(shorter)")
        attach("one-screen-translate-shorter-\(shorter)")
        showSettings()
        assertOneScreen("settingsHome", last: settingsLast, gap: 4...16,
                        shorter: CGFloat(shorter), "设置 −\(shorter)")
        attach("one-screen-settings-shorter-\(shorter)")
        app.terminate()
    }

    func testOneScreenOn932PointPhone() { assertShorterPhoneGetsOneScreen(21) }

    func testOneScreenOn874PointPhone() { assertShorterPhoneGetsOneScreen(82) }

    func testOneScreenOn852PointPhone() { assertShorterPhoneGetsOneScreen(101) }

    func testAIModelPagesListClaudeChatGPTAndDeepSeekFirst() {
        launch()
        showSettings()
        let link = element("aiModelsSettingsLink")
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        link.tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("activeModelCard").exists)
        XCTAssertTrue(element("provider-deepSeek").exists)
        attach("ai-models")

        // One list, no domestic or overseas groups, Claude, ChatGPT and DeepSeek on top.
        element("addProviderButton").tap()
        XCTAssertTrue(app.navigationBars["添加供应商"].waitForExistence(timeout: 5))
        let presets = ["anthropic", "openAI", "deepSeek", "qwen", "doubao", "kimi", "zhipu", "minimax", "gemini"]
            .map { element("preset-" + $0) }
        XCTAssertTrue(presets[0].waitForExistence(timeout: 5))
        XCTAssertTrue(presets[1].exists && presets[2].exists && presets[3].exists)
        let tops = presets.filter { $0.exists }.map { $0.frame.minY }
        XCTAssertEqual(tops, tops.sorted(), "Presets out of order: \(tops)")
        XCTAssertFalse(app.staticTexts["国内"].exists)
        XCTAssertFalse(app.staticTexts["海外"].exists)
        attach("ai-add-provider")

        // 套餐 shows the ChatGPT plan and Claude's API credits. Nothing here is
        // tapped that signs in or opens a page, so the test stays offline.
        let planTab = element("addProviderMode").buttons["套餐"]
        XCTAssertTrue(planTab.waitForExistence(timeout: 5))
        planTab.tap()
        XCTAssertTrue(element("planChatGPTCard").waitForExistence(timeout: 5))
        XCTAssertTrue(element("continueWithChatGPTButton").exists)
        XCTAssertFalse(element("planAccountLink").exists, "Signed out, there is no account row")
        attach("ai-add-provider-plan")
        app.swipeUp()
        XCTAssertTrue(element("planClaudeCard").waitForExistence(timeout: 5))
        XCTAssertTrue(element("planCodingNote").waitForExistence(timeout: 5))
        attach("ai-add-provider-plan-claude")
        element("cancelAddProvider").tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))

        element("provider-deepSeek").tap()
        XCTAssertTrue(app.navigationBars["DeepSeek"].waitForExistence(timeout: 5))
        let flash = element("model-deepseek-flash")
        XCTAssertTrue(flash.waitForExistence(timeout: 5))
        XCTAssertTrue(element("model-deepseek-v4-pro").exists)
        attach("ai-provider-deepseek")
        flash.tap()
        XCTAssertTrue(app.navigationBars["模型"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("apiStylePicker").exists)
        XCTAssertTrue(element("reasoningPicker").exists)
        // The model already in use needs the key to translate, not to be put in use.
        XCTAssertTrue(app.staticTexts["这个供应商还没有填 API Key，填好后才能翻译。"].exists)
        XCTAssertFalse(app.staticTexts["这个供应商还没有填 API Key，填好后才能设为正在用。"].exists)
        attach("ai-model-deepseek-flash")
        app.navigationBars["模型"].buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["DeepSeek"].waitForExistence(timeout: 5))
        app.navigationBars["DeepSeek"].buttons.firstMatch.tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))

        element("switchModelButton").tap()
        XCTAssertTrue(app.navigationBars["换模型"].waitForExistence(timeout: 5))
        XCTAssertTrue(element("switch-deepSeek-deepseek-flash").exists)
        attach("ai-switch-model")
        element("closeModelSwitcher").tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))

        element("aiHelpButton").tap()
        XCTAssertTrue(app.navigationBars["说明"].waitForExistence(timeout: 5))
        attach("ai-help")
        element("closeAIHelp").tap()
        XCTAssertTrue(app.navigationBars["AI 大模型"].waitForExistence(timeout: 5))
        app.terminate()
    }
}

/// The two reader layouts on the book a tester copied into the simulator's
/// `tmp/JerreaderUITestBook.epub`, or on a generated Japanese TXT without one.
/// The book file is only read; the screenshots are kept as attachments.
@MainActor
final class JerreaderReaderLayoutUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private var readerSettings: XCUIElement { app.buttons["阅读设置"] }

    /// The bars only fade out and are hidden from VoiceOver, so XCUITest still
    /// finds their buttons. Whether they show is read from the reveal strips,
    /// which exist only while the immersive layout has its bars away.
    private var revealTop: XCUIElement { app.buttons["reader-reveal-top"] }
    private var revealBottom: XCUIElement { app.buttons["reader-reveal-bottom"] }

    /// Taps a reveal strip near its left end. The top strip's centre lies on
    /// the Dynamic Island, which keeps the touch from the app, and the bottom
    /// one's on the home indicator.
    private func tapStrip(_ strip: XCUIElement, dy: CGFloat) {
        strip.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: dy)).tap()
    }

    /// Opens 阅读设置 with a touch at its centre. In the immersive layout the
    /// page runs under the bars, and XCUITest's accessibility hit test finds
    /// the web view there, so an element tap gives up as not hittable.
    private func openReaderSettings() {
        readerSettings.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
    }

    private func waitUntilGone(_ element: XCUIElement, timeout: TimeInterval, _ message: String) {
        let gone = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: element
        )
        XCTAssertEqual(XCTWaiter.wait(for: [gone], timeout: timeout), .completed, message)
    }

    private func attach(_ name: String) {
        // Let the bars finish fading before the frame is kept.
        Thread.sleep(forTimeInterval: 0.8)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Picks a layout in 阅读设置 → 阅读界面 and closes the sheet.
    private func chooseLayout(_ title: String) {
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 10))
        if revealTop.exists {
            tapStrip(revealTop, dy: 0.8)
            waitUntilGone(revealTop, timeout: 3, "the bars come back before 阅读设置")
        }
        openReaderSettings()
        let sheet = app.navigationBars["阅读与翻译"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        // 界面布局 is a pill picker; each option carries readerLayout-<mode>.
        let segment = app.buttons[title == "沉浸" ? "readerLayout-immersive" : "readerLayout-standard"]
        for _ in 0 ..< 4 where !segment.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(segment.isHittable, "界面布局 must be reachable")
        segment.tap()
        XCTAssertTrue(segment.isSelected)
        sheet.buttons["完成"].tap()
        waitUntilGone(sheet, timeout: 5, "the settings sheet closes")
    }

    /// Keeps the 阅读与翻译 sheet section by section, and its 翻译与 AI page,
    /// for the side-by-side check against Android.
    private func captureSettingsSheet() {
        openReaderSettings()
        let sheet = app.navigationBars["阅读与翻译"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        attach("reader-sheet-1")
        for page in 2 ... 4 {
            app.swipeUp()
            attach("reader-sheet-\(page)")
        }
        let translation = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH '翻译与 AI'"))
            .firstMatch
        for _ in 0 ..< 3 where !translation.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(translation.isHittable, "翻译与 AI must be reachable")
        translation.tap()
        let detail = app.navigationBars["翻译与 AI"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        attach("reader-sheet-translation")
        let back = detail.buttons["阅读与翻译"]
        (back.exists ? back : detail.buttons.firstMatch).tap()
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        sheet.buttons["完成"].tap()
        waitUntilGone(sheet, timeout: 5, "the settings sheet closes")
    }

    func testStandardAndImmersiveLayouts() {
        app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-sample-book-ui-test",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-ApplePersistenceIgnoreState", "YES",
        ]
        app.launch()

        // The bars stay in the element tree either way, so this only waits
        // for the book. A run that stopped early may have left the immersive
        // layout on; chooseLayout brings its bars back first.
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 60), "The sample book must open")

        chooseLayout("标准")
        XCTAssertFalse(revealTop.exists)
        Thread.sleep(forTimeInterval: 5)
        XCTAssertFalse(revealTop.exists, "the standard layout keeps its bars")
        attach("reader-layout-standard")
        captureSettingsSheet()

        chooseLayout("沉浸")
        XCTAssertTrue(revealTop.waitForExistence(timeout: 6), "the immersive layout starts without bars")
        XCTAssertTrue(revealBottom.exists)
        attach("reader-layout-immersive")

        tapStrip(revealTop, dy: 0.8)
        waitUntilGone(revealTop, timeout: 3, "the top strip brings the bars back")
        attach("reader-layout-immersive-bars")
        XCTAssertTrue(revealTop.waitForExistence(timeout: 9), "the bars hide again on their own")

        XCTAssertTrue(revealBottom.exists)
        tapStrip(revealBottom, dy: 0.4)
        waitUntilGone(revealBottom, timeout: 3, "the bottom strip brings the bars back")

        // Leave the simulator in the default layout.
        chooseLayout("标准")
        XCTAssertFalse(revealTop.exists)
        app.terminate()
    }

    /// Opens 阅读设置 and returns its navigation bar.
    private func openSettingsSheet() -> XCUIElement {
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 10))
        openReaderSettings()
        let sheet = app.navigationBars["阅读与翻译"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        return sheet
    }

    private func closeSettingsSheet(_ sheet: XCUIElement) {
        sheet.buttons["完成"].tap()
        waitUntilGone(sheet, timeout: 5, "the settings sheet closes")
    }

    /// Scrolls the sheet a short way without a fling, so a control a few rows
    /// down is not thrown past the top of the screen.
    private func nudgeSheetUp() {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.74))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.50))
        from.press(forDuration: 0.05, thenDragTo: to)
    }

    private func reveal(_ element: XCUIElement, _ message: String) {
        for _ in 0 ..< 14 where !element.isHittable {
            nudgeSheetUp()
        }
        XCTAssertTrue(element.isHittable, message)
    }

    /// Picks a paper in 阅读设置 → 背景 and closes the sheet.
    private func choosePaper(_ title: String) {
        let sheet = openSettingsSheet()
        let swatch = app.buttons["\(title)背景"]
        reveal(swatch, "the \(title) paper must be reachable")
        swatch.tap()
        XCTAssertTrue(swatch.isSelected, "\(title) must become the paper")
        closeSettingsSheet(sheet)
    }

    /// Drags 阅读设置 → 版面 → 上下边距 to one end and closes the sheet.
    private func setVerticalMargins(toEnd position: CGFloat, expecting label: String) {
        let sheet = openSettingsSheet()
        let slider = app.sliders["上下边距"]
        reveal(slider, "上下边距 must be reachable")
        slider.adjust(toNormalizedSliderPosition: position)
        XCTAssertTrue(
            app.staticTexts[label].waitForExistence(timeout: 3),
            "上下边距 must read \(label) at this end"
        )
        closeSettingsSheet(sheet)
    }

    private var mockTranslation: XCUIElement {
        app.staticTexts
            .matching(NSPredicate(format: "label CONTAINS %@", "Mock 翻译结果"))
            .firstMatch
    }

    /// Taps a sentence of the sample text and waits for its translation, so
    /// the frame kept afterwards shows the highlight the tap drew.
    private func translateASentence() {
        let page = app.webViews.firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 20), "the page must be showing")
        // The page is tapped by position: how a web view hands its text to
        // accessibility is not something this check should depend on. One
        // point can fall between two lines or columns, so a few are tried,
        // spread both ways; all of them are in the upper half, where the
        // first page has text, and none is near a page-turn edge.
        let points: [(CGFloat, CGFloat)] = [
            (0.50, 0.30), (0.46, 0.36), (0.54, 0.24), (0.42, 0.42), (0.58, 0.33), (0.38, 0.27),
        ]
        for (x, y) in points {
            page.coordinate(withNormalizedOffset: CGVector(dx: x, dy: y)).tap()
            if mockTranslation.waitForExistence(timeout: 6) { break }
        }
        XCTAssertTrue(mockTranslation.exists, "a tap on a sentence must translate it")
        Thread.sleep(forTimeInterval: 0.6)
    }

    /// Puts the translation card away, and with it the highlight on the page.
    private func closeTranslation() {
        let close = app.buttons["关闭翻译"]
        XCTAssertTrue(close.waitForExistence(timeout: 5), "the card has 关闭翻译")
        close.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        waitUntilGone(mockTranslation, timeout: 5, "关闭翻译 puts the card away")
    }

    /// 2.0.1. Two things a reader reported after 2.0.0:
    ///
    /// - the highlight a tap draws kept its outline and lost its fill on every
    ///   paper but plain white, because ReadiumCSS forces backgrounds inside
    ///   the page to transparent on tinted and dark papers;
    /// - only the side margins could be set, not the gap above and below.
    ///
    /// The frames kept here are the evidence: a tapped sentence on each
    /// paper, then the page with 上下边距 at each end. Whether the fill is
    /// there is read off the frames; the unit test
    /// `testQuickSentenceHighlightKeepsItsFillUnderReadiumThemeRules` holds
    /// the computed colours.
    func testSelectionFillOnEveryPaperAndVerticalMargins() {
        // Six trips through the settings sheet and four translations: about
        // two minutes on this simulator, which is the default allowance.
        executionTimeAllowance = 360
        app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-sample-book-ui-test", "--jerreader-sample-text-ui-test",
            "--jerreader-mock-translation-ui-test",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-ApplePersistenceIgnoreState", "YES",
        ]
        app.launch()
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 60), "The sample book must open")
        chooseLayout("标准")

        for (paper, name) in [("护眼", "sepia"), ("冷灰", "cool-gray"), ("深色", "dark"), ("浅色", "light")] {
            choosePaper(paper)
            translateASentence()
            attach("reader-selection-\(name)")
            closeTranslation()
        }

        setVerticalMargins(toEnd: 1, expecting: "宽")
        attach("reader-vertical-margins-widest")
        setVerticalMargins(toEnd: 0, expecting: "窄")
        attach("reader-vertical-margins-none")
        app.terminate()
    }

    /// 2.0 opens a book from the shelf with the iOS 18 zoom transition, which
    /// on its own can also be dismissed by a downward swipe or a pinch. In a
    /// reader that would close the book by accident, so the reader turns
    /// those gestures off; only 关闭阅读器 closes it.
    func testReaderOpenedFromShelfIgnoresDismissGestures() {
        app = XCUIApplication()
        app.launchArguments = [
            "--jerreader-sample-book-ui-test",
            "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN",
            "-ApplePersistenceIgnoreState", "YES",
        ]
        app.launch()
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 60), "The sample book must open")
        chooseLayout("标准")

        let close = app.buttons["关闭阅读器"]
        XCTAssertTrue(close.waitForExistence(timeout: 5))
        close.tap()
        waitUntilGone(readerSettings, timeout: 8, "关闭阅读器 returns to the shelf")

        // The book was just read, so the shelf leads with its 继续阅读 card,
        // whose cover is a zoom source like the grid covers.
        let hero = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "继续阅读，")).firstMatch
        XCTAssertTrue(hero.waitForExistence(timeout: 8), "the shelf shows the book just read")
        attach("library-continue-reading")
        hero.tap()
        XCTAssertTrue(readerSettings.waitForExistence(timeout: 30), "the 继续阅读 card opens the reader")
        Thread.sleep(forTimeInterval: 1.5)

        func assertStillReading(_ gesture: String) {
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertTrue(readerSettings.exists, "\(gesture) must not close the reader")
            XCTAssertTrue(close.exists, "\(gesture) must not close the reader")
        }
        app.swipeDown(velocity: .fast)
        assertStillReading("a fast downward swipe")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.28))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.96)))
        assertStillReading("a long downward drag")
        app.pinch(withScale: 0.4, velocity: -2)
        assertStillReading("a pinch")
        attach("reader-after-dismiss-gestures")

        close.tap()
        waitUntilGone(readerSettings, timeout: 8, "关闭阅读器 still closes the reader")
        XCTAssertTrue(hero.waitForExistence(timeout: 8), "the reader closes back to the shelf")
        app.terminate()
    }
}
