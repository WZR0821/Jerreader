import CryptoKit
import Foundation
import PDFKit
@preconcurrency import ReadiumShared
import ReadiumZIPFoundation
import SwiftData
import UIKit
@preconcurrency import WebKit
import XCTest
@testable import JerreaderUnified

@MainActor
final class LibraryTests: XCTestCase {
    func testBookRecordPersistsInSwiftData() throws {
        let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        let container = try ModelContainer(
            for: BookRecord.self,
            configurations: configuration
        )
        let context = container.mainContext
        context.insert(BookRecord(
            title: "测试书籍",
            author: "测试作者",
            language: "ja",
            localFileName: "book.epub",
            fileFingerprint: "fingerprint"
        ))
        try context.save()

        let records = try context.fetch(FetchDescriptor<BookRecord>())
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.title, "测试书籍")
        XCTAssertEqual(records.first?.format, .epub)
    }

    func testBookRecordNormalizesProgressStatsCategoryAndTags() {
        let book = BookRecord(
            title: "Long Read",
            author: "Reader",
            localFileName: "long.epub",
            fileFingerprint: "organization-test",
            lastReadProgress: 1.8,
            totalReadingSeconds: -10,
            category: "文学",
            series: " 夜读 ",
            tags: [" 日本 ", "文学", "日本", ""]
        )

        XCTAssertEqual(book.lastReadProgress, 1)
        XCTAssertEqual(book.totalReadingSeconds, 0)
        XCTAssertEqual(book.series, "夜读")
        XCTAssertEqual(book.tags, ["日本", "文学"])

        book.updateOrganization(
            category: "  长篇  ",
            series: " 文库系列 ",
            tags: ["Classic", "classic", " 阅读 "]
        )
        XCTAssertEqual(book.category, "长篇")
        XCTAssertEqual(book.series, "文库系列")
        XCTAssertEqual(book.tags, ["Classic", "阅读"])
    }

    func testBookRecordClampsFollowReadingRate() {
        let fast = BookRecord(
            title: "Fast",
            author: "Reader",
            localFileName: "fast.epub",
            fileFingerprint: "speech-fast",
            readerSpeechRate: 9
        )
        let slow = BookRecord(
            title: "Slow",
            author: "Reader",
            localFileName: "slow.epub",
            fileFingerprint: "speech-slow",
            readerSpeechRate: 0.1
        )

        XCTAssertEqual(fast.readerSpeechRate, 2)
        XCTAssertEqual(slow.readerSpeechRate, 0.5)
    }

    func testBookRecordDefaultsToPublicationTextOrientation() {
        let book = BookRecord(
            title: "縦書き",
            author: "作者",
            language: nil,
            localFileName: "vertical.epub",
            fileFingerprint: "orientation-default"
        )

        XCTAssertEqual(
            book.readerTextOrientation,
            ReaderTextOrientationChoice.publication.rawValue
        )
    }

    func testOpeningTheSameBookCreatesANewReaderSessionIdentity() {
        let book = BookRecord(
            title: "Repeated Open",
            author: "Reader",
            localFileName: "repeat.epub",
            fileFingerprint: "repeat-session"
        )

        let first = LibraryReaderSession(book: book)
        let second = LibraryReaderSession(book: book)

        XCTAssertTrue(first.book === second.book)
        XCTAssertNotEqual(first.id, second.id)
    }

    func testBookDeletionStagesBookmarksAndAnnotationsWithoutTouchingOtherBooks() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingBookmarkRecord.self,
            ReadingAnnotationRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let context = container.mainContext
        let deletedBookID = UUID()
        let retainedBookID = UUID()

        context.insert(ReadingBookmarkRecord(
            bookmarkKey: "deleted-bookmark",
            bookID: deletedBookID,
            bookTitle: "Deleted",
            locatorJSON: #"{"href":"chapter-1"}"#,
            chapterTitle: "Chapter 1",
            progress: 0.2
        ))
        context.insert(ReadingBookmarkRecord(
            bookmarkKey: "retained-bookmark",
            bookID: retainedBookID,
            bookTitle: "Retained",
            locatorJSON: #"{"href":"chapter-2"}"#,
            chapterTitle: "Chapter 2",
            progress: 0.4
        ))
        context.insert(ReadingAnnotationRecord(
            annotationKey: "deleted-annotation",
            bookID: deletedBookID,
            bookTitle: "Deleted",
            locatorJSON: #"{"href":"chapter-1"}"#,
            selectedText: "Selected text",
            chapterTitle: "Chapter 1",
            progress: 0.2
        ))
        context.insert(ReadingAnnotationRecord(
            annotationKey: "retained-annotation",
            bookID: retainedBookID,
            bookTitle: "Retained",
            locatorJSON: #"{"href":"chapter-2"}"#,
            selectedText: "Retained text",
            chapterTitle: "Chapter 2",
            progress: 0.4
        ))
        try context.save()

        try LibraryBookDeletion.stageAssociatedRecords(
            for: deletedBookID,
            in: context
        )
        try context.save()

        let bookmarks = try context.fetch(FetchDescriptor<ReadingBookmarkRecord>())
        let annotations = try context.fetch(FetchDescriptor<ReadingAnnotationRecord>())
        XCTAssertEqual(bookmarks.map(\.bookID), [retainedBookID])
        XCTAssertEqual(annotations.map(\.bookID), [retainedBookID])
    }

    func testLastOpenedTextUsesMinuteHourAndDayBucketsWithoutSeconds() {
        let now = Date(timeIntervalSince1970: 1_000_000)

        XCTAssertEqual(
            JerreaderRelativeTime.string(from: now.addingTimeInterval(-12), now: now),
            "刚刚"
        )
        XCTAssertEqual(
            JerreaderRelativeTime.string(from: now.addingTimeInterval(-125), now: now),
            "2 分钟前"
        )
        XCTAssertEqual(
            JerreaderRelativeTime.string(from: now.addingTimeInterval(-7_400), now: now),
            "2 小时前"
        )
        XCTAssertEqual(
            JerreaderRelativeTime.string(from: now.addingTimeInterval(-180_000), now: now),
            "2 天前"
        )
    }

    func testSupportedDocumentExtensionsResolveToExpectedFormats() {
        XCTAssertEqual(BookFormat(fileURL: URL(fileURLWithPath: "/tmp/book.EPUB")), .epub)
        XCTAssertEqual(BookFormat(fileURL: URL(fileURLWithPath: "/tmp/paper.pdf")), .pdf)
        XCTAssertEqual(BookFormat(fileURL: URL(fileURLWithPath: "/tmp/notes.docx")), .docx)
        XCTAssertEqual(BookFormat(fileURL: URL(fileURLWithPath: "/tmp/story.txt")), .text)
        XCTAssertNil(BookFormat(fileURL: URL(fileURLWithPath: "/tmp/legacy.doc")))
    }

    func testTXTImportBuildsAValidatedReflowableEPUB() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("静かな夜.txt")
        try Data("静かな夜でした。\n\n彼は本を閉じ、窓の外を見た。".utf8)
            .write(to: sourceURL)

        let service = EPUBImportService(rootDirectoryURL: root)
        let imported = try await service.importBook(
            from: sourceURL,
            existingFingerprints: []
        )
        let localURL = try LibraryPaths.bookURL(
            fileName: imported.localFileName,
            rootDirectoryURL: root
        )

        XCTAssertEqual(imported.sourceFormat, .text)
        XCTAssertEqual(imported.title, "静かな夜")
        XCTAssertEqual(localURL.pathExtension, "epub")
        XCTAssertTrue(FileManager.default.fileExists(atPath: localURL.path))
        XCTAssertGreaterThan(try Data(contentsOf: localURL).count, 100)
    }

    func testFreshImportedEPUBOpensAndBuildsNavigatorOnFirstAttempt() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("first-open.txt")
        try Data("初回から開ける本文です。\n\n二つ目の段落です。".utf8)
            .write(to: sourceURL)
        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )

        let publication = try await EPUBPublicationService(rootDirectoryURL: root)
            .open(book: book)
        XCTAssertFalse(publication.readingOrder.isEmpty)

        var controller: EPUBReaderViewController? = try EPUBReaderViewController(
            publication: publication,
            initialLocation: nil,
            fontSize: 1,
            theme: .coolGray,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .horizontal,
            bookLanguage: imported.language
        )
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.windowLevel = .alert + 1
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller?.loadViewIfNeeded()
        controller?.view.frame = window.bounds
        controller?.view.layoutIfNeeded()
        XCTAssertNotNil(controller?.navigator.view)

        var firstSpineScriptInstalled = false
        for _ in 0 ..< 200 {
            if let webView = firstWebView(in: controller?.navigator.view),
               let installed = try? await webView.evaluateJavaScript(
                   "globalThis.__jerreaderReaderSelectionBridgeVersion === 3"
               ) as? Bool,
               installed
            {
                firstSpineScriptInstalled = true
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(
            firstSpineScriptInstalled,
            "The selection policy must be injected before the first Readium spine WebView loads"
        )

        window.rootViewController = nil
        window.isHidden = true
        controller = nil
        publication.close()
    }

    /// End-to-end proof for the one-tap vertical/horizontal switch on the book
    /// shape that used to defeat it: a Japanese publication whose own
    /// stylesheet pins `vertical-rl`. Readium's `cjk-horizontal` variant
    /// declares no writing mode at all, so before the writing-mode policy the
    /// text stayed vertical here no matter which preferences were submitted.
    func testVerticalJapaneseEPUBSwitchesToHorizontalAndBackWithReadingDirection() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("vertical-source.epub")
        try await writeVerticalJapaneseEPUB(to: sourceURL)
        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )
        let publication = try await EPUBPublicationService(rootDirectoryURL: root)
            .open(book: book)
        defer { publication.close() }

        let layout = await JapanesePublicationLayoutDetector.detect(
            in: publication,
            fallbackLanguage: imported.language
        )
        XCTAssertEqual(
            layout,
            .verticalRTL,
            "The fixture must be recognised as a right-to-left vertical book"
        )

        var controller: EPUBReaderViewController? = try EPUBReaderViewController(
            publication: publication,
            initialLocation: nil,
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .publication,
            publicationLayout: layout,
            readingMode: .paginated,
            customBackgroundHex: "",
            bookLanguage: imported.language
        )
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.windowLevel = .alert + 1
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.rootViewController = nil
            window.isHidden = true
            controller = nil
        }
        controller?.loadViewIfNeeded()
        controller?.view.frame = window.bounds
        controller?.view.layoutIfNeeded()

        // Reading the writing mode while the chapter is still loading is
        // meaningless: before its stylesheet is applied even a vertical book
        // computes as `horizontal-tb`, which would make this test pass without
        // the policy doing anything. Only a fully loaded document counts, and
        // the value has to survive a confirmation pass.
        func chapterWritingMode() async -> String? {
            for webView in allWebViews(in: controller?.navigator.view) {
                guard let value = try? await webView.evaluateJavaScript(
                    """
                    (() => {
                      if (document.readyState !== 'complete') return null;
                      if (!document.getElementById('paragraph')) return null;
                      return getComputedStyle(document.documentElement).writingMode;
                    })()
                    """
                ) as? String, !value.isEmpty else { continue }
                return value
            }
            return nil
        }

        func settledWritingMode(
            expecting expected: String,
            timeoutSteps: Int = 240
        ) async -> String? {
            var last: String?
            for _ in 0 ..< timeoutSteps {
                controller?.view.layoutIfNeeded()
                let value = await chapterWritingMode()
                if let value {
                    last = value
                    if value == expected {
                        try? await Task.sleep(for: .milliseconds(250))
                        controller?.view.layoutIfNeeded()
                        let confirmed = await chapterWritingMode()
                        if confirmed == expected { return expected }
                        last = confirmed ?? last
                    }
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
            return last
        }

        // 原版：竖排 + 从右向左翻页。
        let verticalMode = await settledWritingMode(expecting: "vertical-rl")
        XCTAssertEqual(verticalMode, "vertical-rl")
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .rtl)
        XCTAssertEqual(controller?.navigator.settings.verticalText, true)

        // 一键横排：文字必须真的变横，翻页方向必须变成从左向右。
        controller?.applyPreferences(
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .horizontal,
            readingMode: .paginated,
            customBackgroundHex: ""
        )
        let horizontalMode = await settledWritingMode(expecting: "horizontal-tb")
        XCTAssertEqual(
            horizontalMode,
            "horizontal-tb",
            "The publisher's own vertical CSS must not survive the switch"
        )
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .ltr)
        XCTAssertEqual(controller?.navigator.settings.verticalText, false)

        // 切回原版：把版式交还给出版物，方向也回到从右向左。
        controller?.applyPreferences(
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .publication,
            readingMode: .paginated,
            customBackgroundHex: ""
        )
        let restoredMode = await settledWritingMode(expecting: "vertical-rl")
        XCTAssertEqual(restoredMode, "vertical-rl")
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .rtl)
        XCTAssertEqual(controller?.navigator.settings.verticalText, true)
    }

    /// Runs the same vertical/horizontal round trip against a real book on
    /// disk. Publications differ wildly in *where* they declare the vertical
    /// mode — on `<html>` via a class, on `html` via an element selector, on
    /// an inner wrapper, or nowhere at all (leaving it to Readium) — so a
    /// suspect book can be checked directly. The simulator does not inherit
    /// the shell environment, so the variable needs the `TEST_RUNNER_` prefix
    /// that xcodebuild forwards and strips:
    ///
    ///     TEST_RUNNER_JERREADER_LAYOUT_EPUB_PATH="/path/to/book.epub" \
    ///       xcodebuild test -project Jerreader.xcodeproj -scheme Jerreader \
    ///       -destination 'platform=iOS Simulator,name=iPhone 16 Pro' \
    ///       -only-testing:JerreaderTests/LibraryTests/testExternalEPUBSwitchesBetweenVerticalAndHorizontal \
    ///       CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
    ///
    /// No book is committed to the repository; without the variable this skips.
    func testExternalEPUBSwitchesBetweenVerticalAndHorizontal() async throws {
        guard let path = ProcessInfo.processInfo.environment[
            "JERREADER_LAYOUT_EPUB_PATH"
        ], !path.isEmpty else {
            throw XCTSkip("Set JERREADER_LAYOUT_EPUB_PATH to check a real book")
        }
        let sourceURL = URL(fileURLWithPath: path)
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: sourceURL.path),
            "No EPUB at \(sourceURL.path)"
        )

        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: root) }

        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )
        let publication = try await EPUBPublicationService(rootDirectoryURL: root)
            .open(book: book)
        defer { publication.close() }

        let layout = await JapanesePublicationLayoutDetector.detect(
            in: publication,
            fallbackLanguage: imported.language
        )
        XCTAssertTrue(
            layout.verticalText,
            "\(imported.title) was not detected as a vertical publication"
        )

        // Start on a spine item that actually carries body text. The opening
        // items of a real book are a cover and a title page, and those are
        // exactly the pages whose markup is least representative.
        let htmlLinks = publication.readingOrder.filter {
            $0.mediaType?.isHTML != false
        }
        let textLink = htmlLinks.isEmpty
            ? nil
            : htmlLinks[min(htmlLinks.count / 3, htmlLinks.count - 1)]
        let initialLocation = textLink.flatMap { link -> Locator? in
            guard let href = AnyURL(string: link.href) else { return nil }
            return Locator(
                href: href,
                mediaType: link.mediaType ?? .xhtml,
                title: link.title,
                locations: .init(progression: 0)
            )
        }

        var controller: EPUBReaderViewController? = try EPUBReaderViewController(
            publication: publication,
            initialLocation: initialLocation,
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .publication,
            publicationLayout: layout,
            readingMode: .paginated,
            customBackgroundHex: "",
            bookLanguage: imported.language
        )
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.windowLevel = .alert + 1
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.rootViewController = nil
            window.isHidden = true
            controller = nil
        }
        controller?.loadViewIfNeeded()
        controller?.view.frame = window.bounds
        controller?.view.layoutIfNeeded()

        func loadedWritingMode() async -> String? {
            for webView in allWebViews(in: controller?.navigator.view) {
                guard let value = try? await webView.evaluateJavaScript(
                    """
                    (() => {
                      if (document.readyState !== 'complete') return null;
                      if (!document.body?.textContent?.trim()) return null;
                      return getComputedStyle(document.documentElement).writingMode;
                    })()
                    """
                ) as? String, !value.isEmpty else { continue }
                return value
            }
            return nil
        }

        func settled(expecting expected: String) async -> String? {
            var last: String?
            for _ in 0 ..< 240 {
                controller?.view.layoutIfNeeded()
                if let value = await loadedWritingMode() {
                    last = value
                    if value == expected {
                        try? await Task.sleep(for: .milliseconds(250))
                        controller?.view.layoutIfNeeded()
                        let confirmed = await loadedWritingMode()
                        if confirmed == expected { return expected }
                        last = confirmed ?? last
                    }
                }
                try? await Task.sleep(for: .milliseconds(50))
            }
            return last
        }

        let openedMode = await settled(expecting: "vertical-rl")
        XCTAssertEqual(
            openedMode,
            "vertical-rl",
            "\(imported.title) did not open as a vertical book"
        )
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .rtl)

        controller?.applyPreferences(
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .horizontal,
            readingMode: .paginated,
            customBackgroundHex: ""
        )
        let horizontalMode = await settled(expecting: "horizontal-tb")
        XCTAssertEqual(
            horizontalMode,
            "horizontal-tb",
            "\(imported.title) did not switch to horizontal"
        )
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .ltr)

        controller?.applyPreferences(
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .publication,
            readingMode: .paginated,
            customBackgroundHex: ""
        )
        let restoredMode = await settled(expecting: "vertical-rl")
        XCTAssertEqual(
            restoredMode,
            "vertical-rl",
            "\(imported.title) did not return to the publication layout"
        )
        XCTAssertEqual(controller?.navigator.settings.readingProgression, .rtl)
    }

    func testExternalRubyEPUBRecoversBaseSelectionInHorizontalAndVerticalModes() async throws {
        let resourceName = ProcessInfo.processInfo.environment[
            "JERREADER_RUBY_EPUB_RESOURCE"
        ] ?? (Bundle.main.url(
            forResource: "RealRubyBook",
            withExtension: "epub"
        ) == nil ? nil : "RealRubyBook")
        guard let resourceName else {
            throw XCTSkip("Set JERREADER_RUBY_EPUB_RESOURCE for a real EPUB check")
        }
        let sourceURL = try XCTUnwrap(
            Bundle.main.url(forResource: resourceName, withExtension: "epub"),
            "The requested real EPUB fixture is not present in the test host"
        )
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )
        let publication = try await EPUBPublicationService(rootDirectoryURL: root)
            .open(book: book)
        defer { publication.close() }
        let rubyChapter = try XCTUnwrap(
            publication.readingOrder.first(where: { $0.href.contains("part0005") }),
            "The supplied verification book no longer contains the expected ruby chapter"
        )
        let rubyHref = try XCTUnwrap(
            AnyURL(string: rubyChapter.href),
            "The real ruby chapter has an invalid HREF"
        )
        let rubyLocator = Locator(
            href: rubyHref,
            mediaType: rubyChapter.mediaType ?? .xhtml,
            title: rubyChapter.title,
            locations: .init(progression: 0)
        )

        var controller: EPUBReaderViewController? = try EPUBReaderViewController(
            publication: publication,
            initialLocation: rubyLocator,
            fontSize: 1,
            theme: .coolGray,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1,
            pageMargins: 1,
            textOrientation: .horizontal,
            bookLanguage: imported.language
        )
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        window.windowLevel = .alert + 1
        window.rootViewController = controller
        window.makeKeyAndVisible()
        defer {
            window.rootViewController = nil
            window.isHidden = true
            controller = nil
        }
        controller?.loadViewIfNeeded()
        controller?.view.frame = window.bounds
        controller?.view.layoutIfNeeded()

        var rubyWebView: WKWebView?
        for _ in 0 ..< 300 {
            for candidate in allWebViews(in: controller?.navigator.view) {
                guard let controller,
                      candidate.convert(candidate.bounds, to: controller.view)
                        .intersects(controller.view.bounds)
                else { continue }
                let state = try? await candidate.evaluateJavaScript(
                    """
                    ({
                      rubyCount: document.querySelectorAll('ruby > rt').length,
                      bridgeVersion: globalThis.__jerreaderReaderSelectionBridgeVersion || 0
                    })
                    """
                ) as? [String: Any]
                if (state?["rubyCount"] as? NSNumber)?.intValue ?? 0 > 0,
                   (state?["bridgeVersion"] as? NSNumber)?.intValue == 3 {
                    rubyWebView = candidate
                    break
                }
            }
            if rubyWebView != nil { break }
            try await Task.sleep(for: .milliseconds(20))
        }
        let webView = try XCTUnwrap(
            rubyWebView,
            "Readium did not render the real ruby chapter with selection bridge v3"
        )

        let fixtureRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              const ruby = Array.from(document.querySelectorAll('ruby')).find((candidate) =>
                Array.from(candidate.children).filter((child) => child.matches('rt')).length > 1
              );
              if (!ruby) return null;
              const reading = Array.from(ruby.children).find((child) => child.matches('rt'));

              const children = Array.from(ruby.childNodes);
              const directReading = children.find(
                (child) => child === reading || child.contains?.(reading)
              );
              const readingIndex = children.indexOf(directReading);
              let segmentStart = 0;
              for (let index = readingIndex - 1; index >= 0; index -= 1) {
                const child = children[index];
                const element = child.nodeType === Node.ELEMENT_NODE ? child : null;
                if (element?.matches?.('rt,rp') || element?.querySelector?.('rt,rp')) {
                  segmentStart = index + 1;
                  break;
                }
              }
              const expectedBase = children
                .slice(segmentStart, readingIndex)
                .map((child) => {
                  const clone = child.cloneNode(true);
                  if (clone.querySelectorAll) {
                    clone.querySelectorAll('rt,rp').forEach((node) => node.remove());
                  }
                  return clone.textContent || '';
                })
                .join('');
              // The v3 policy leaves no reading text to select. Selecting the
              // base segment in front of the first annotation is the closest
              // physical analogue of a long press on that kanji group.
              const range = document.createRange();
              range.setStart(ruby, segmentStart);
              range.setEnd(ruby, readingIndex);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              ruby.scrollIntoView({ block: 'center', inline: 'center' });

              const style = getComputedStyle(reading);
              const rect = reading.getBoundingClientRect();
              const hit = document.elementFromPoint(
                rect.left + rect.width / 2,
                rect.top + rect.height / 2
              );
              globalThis.__jerreaderRealRubyRuby = ruby;
              globalThis.__jerreaderRealRubySegmentStart = segmentStart;
              globalThis.__jerreaderRealRubySegmentEnd = readingIndex;
              globalThis.__jerreaderRealRubyExpectedBase = expectedBase;
              return {
                expectedBase,
                rawRange: range.toString(),
                selectionText: selection.toString(),
                readingDetached: reading.firstChild === null &&
                  (reading.dataset.jerreaderRt || '').length > 0,
                annotationStillRendered: rect.width > 1 && rect.height > 1,
                userSelect: style.userSelect || style.webkitUserSelect,
                pointerEvents: style.pointerEvents,
                hitIsAnnotation: Boolean(hit?.closest?.('rt,rp'))
              };
            })()
            """
        )
        let fixture = try XCTUnwrap(fixtureRaw as? [String: Any])
        let expectedBase = TranslationCacheStore.normalizedText(
            try XCTUnwrap(fixture["expectedBase"] as? String)
        )
        XCTAssertFalse(expectedBase.isEmpty)
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                fixture["rawRange"] as? String ?? ""
            ),
            expectedBase
        )
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                fixture["selectionText"] as? String ?? ""
            ),
            expectedBase
        )
        XCTAssertEqual(fixture["readingDetached"] as? Bool, true)
        XCTAssertEqual(fixture["annotationStillRendered"] as? Bool, true)
        XCTAssertEqual(fixture["userSelect"] as? String, "none")
        XCTAssertEqual(fixture["pointerEvents"] as? String, "none")
        XCTAssertEqual(fixture["hitIsAnnotation"] as? Bool, false)

        let horizontalRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let horizontal = try XCTUnwrap(horizontalRaw as? [String: Any])
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                horizontal["normalizedText"] as? String ?? ""
            ),
            expectedBase
        )
        XCTAssertEqual(horizontal["recoveredFromRuby"] as? Bool, false)
        XCTAssertGreaterThan(
            (horizontal["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )

        let bridge = EPUBSelectionBridge()
        if let controller {
            bridge.attach(to: controller.view)
            controller.view.layoutIfNeeded()
        }
        let horizontalSnapshot = try XCTUnwrap(
            EPUBSelectionSnapshot(javaScriptValue: horizontalRaw)
        )
        let horizontalFrame = bridge.display(
            snapshot: horizontalSnapshot,
            from: webView
        )
        let horizontalGeometry = """
        DOM=\(horizontalSnapshot.localRects)
        webView.frame=\(webView.frame) bounds=\(webView.bounds)
        webViewInController=\(webView.convert(webView.bounds, to: controller?.view))
        controller.bounds=\(String(describing: controller?.view.bounds))
        """
        XCTAssertNotNil(horizontalFrame, horizontalGeometry)
        XCTAssertFalse(bridge.displayedRects.isEmpty, horizontalGeometry)
        try await Task.sleep(for: .milliseconds(120))
        let horizontalImage = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
            window.layer.render(in: context.cgContext)
        }
        let horizontalAttachment = XCTAttachment(image: horizontalImage)
        horizontalAttachment.name = "real-epub-horizontal-kanji-selection"
        horizontalAttachment.lifetime = .keepAlways
        add(horizontalAttachment)

        _ = try await webView.evaluateJavaScript(
            """
            (() => {
              document.documentElement.style.writingMode = 'vertical-rl';
              document.documentElement.style.textOrientation = 'mixed';
              const ruby = globalThis.__jerreaderRealRubyRuby;
              const range = document.createRange();
              range.setStart(ruby, globalThis.__jerreaderRealRubySegmentStart);
              range.setEnd(ruby, globalThis.__jerreaderRealRubySegmentEnd);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              ruby.scrollIntoView({
                block: 'center',
                inline: 'center'
              });
              return getComputedStyle(document.documentElement).writingMode;
            })()
            """
        )
        try await Task.sleep(for: .milliseconds(80))
        let verticalRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let vertical = try XCTUnwrap(verticalRaw as? [String: Any])
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                vertical["normalizedText"] as? String ?? ""
            ),
            expectedBase
        )
        XCTAssertEqual(vertical["recoveredFromRuby"] as? Bool, false)
        XCTAssertGreaterThan(
            (vertical["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )
        let verticalSnapshot = try XCTUnwrap(
            EPUBSelectionSnapshot(javaScriptValue: verticalRaw)
        )
        let verticalFrame = bridge.display(
            snapshot: verticalSnapshot,
            from: webView
        )
        let verticalGeometry = """
        DOM=\(verticalSnapshot.localRects)
        webView.frame=\(webView.frame) bounds=\(webView.bounds)
        webViewInController=\(webView.convert(webView.bounds, to: controller?.view))
        controller.bounds=\(String(describing: controller?.view.bounds))
        """
        XCTAssertNotNil(verticalFrame, verticalGeometry)
        try await Task.sleep(for: .milliseconds(120))
        let verticalImage = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
            window.layer.render(in: context.cgContext)
        }
        let verticalAttachment = XCTAttachment(image: verticalImage)
        verticalAttachment.name = "real-epub-vertical-kanji-selection"
        verticalAttachment.lifetime = .keepAlways
        add(verticalAttachment)
    }

    func testNativeEPUBImportCanBeOpenedImmediatelyOnFirstAttempt() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("immediate-open.epub")
        try await ReflowableEPUBBuilder.build(
            content: ReflowableDocumentContent(
                title: "Immediate Open",
                author: "Jerreader Tests",
                language: "ja-JP",
                paragraphs: ["導入直後の一回目から開きます。"]
            ),
            destinationURL: sourceURL
        )
        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )

        let publication = try await EPUBPublicationService(rootDirectoryURL: root)
            .open(book: book)
        XCTAssertEqual(publication.metadata.title, "Immediate Open")
        XCTAssertFalse(publication.readingOrder.isEmpty)
        publication.close()
    }

    func testDOCXParserKeepsBaseKanjiAndDropsRubyAnnotation() throws {
        let documentXML = Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:p>
              <w:r><w:t>私は</w:t></w:r>
              <w:ruby>
                <w:rt><w:r><w:t>とうきょう</w:t></w:r></w:rt>
                <w:rubyBase><w:r><w:t>東京</w:t></w:r></w:rubyBase>
              </w:ruby>
              <w:r><w:t>で学ぶ。</w:t></w:r>
            </w:p>
          </w:body>
        </w:document>
        """.utf8)
        let coreXML = Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties
          xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
          xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>日本語の読書</dc:title>
          <dc:creator>読鼠</dc:creator>
          <dc:language>ja-JP</dc:language>
        </cp:coreProperties>
        """.utf8)

        let content = try DOCXDocumentParser.parse(
            documentXML: documentXML,
            corePropertiesXML: coreXML,
            fallbackTitle: "fallback"
        )

        XCTAssertEqual(content.title, "日本語の読書")
        XCTAssertEqual(content.author, "読鼠")
        XCTAssertEqual(content.language, "ja-JP")
        XCTAssertEqual(content.paragraphs, ["私は東京で学ぶ。"])
        XCTAssertFalse(content.plainText.contains("とうきょう"))
    }

    func testDOCXImportExtractsArchiveAndBuildsReadableEPUB() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let fixtureBase64 = "UEsDBAoAAAAAAMV98lwAAAAAAAAAAAAAAAAFABwAd29yZC9VVAkAAzEhW2o+IVtqdXgLAAEE9QEAAAQUAAAAUEsDBBQAAAAIAMV98lzOcQTD2gAAACEBAAARABwAd29yZC9kb2N1bWVudC54bWxVVAkAAzEhW2oxIVtqdXgLAAEE9QEAAAQUAAAAs7GvyM1RKEstKs7Mz7NVMtQzUFJIzUvOT8nMS7dVCg1x07VQsrfjsim3SslPLs1NzStRAGrIK7Yqt1XKKCkpsNLXL07OSM1NLNbLL0jNA8ql5RflJpYAuUXp+uX5RSkFRfnJqcXFQPNyc/SNDAzM9HMTM/OU7LgUFICmJuWnVIKYYE6BHZAoAhEldi7+zhFAl6ToluTrAimFgsSixPSixIIMPRt9kDyILAKTBVj1P5u+9NmcNS9WzXvcuO7Zuq0vJux93Lj8cePMxw1NWA0AMSCOAbFgnrXjAgBQSwMECgAAAAAAxX3yXAAAAAAAAAAAAAAAAAkAHABkb2NQcm9wcy9VVAkAAzEhW2o+IVtqdXgLAAEE9QEAAAQUAAAAUEsDBBQAAAAIAMV98lwRFuMB1wAAAEkBAAARABwAZG9jUHJvcHMvY29yZS54bWxVVAkAAzEhW2oxIVtqdXgLAAEE9QEAAAQUAAAAZZDBTsMwDIbvfYoo99YtB4SqNjswTWIXdhgSVysxWUebRI6HeHxCNXrhFuf//Mn2sPteZvVFnKcYRt01rVYUbHRT8KN+Ox/qJ70z1WBTbyPTiWMilolypVTpDLm3adQXkdQDZHuhBXNTmFDCj8gLSinZQ0L7iZ7goW0fYSFBh4Lwq6zT5tSb1NlNmm48rwpngWZaKEiGrulAm4IPzvYyyUxm//r8rl6CkGeUsswAW3TnLBNKZHMkLi9HrM6UJa/gX3ZHZwz+VuY1V6yPp5XYvqoB/l3DVD9QSwECHgMKAAAAAADFffJcAAAAAAAAAAAAAAAABQAYAAAAAAAAABAA7UEAAAAAd29yZC9VVAUAAzEhW2p1eAsAAQT1AQAABBQAAABQSwECHgMUAAAACADFffJcznEEw9oAAAAhAQAAEQAYAAAAAAABAAAApIE/AAAAd29yZC9kb2N1bWVudC54bWxVVAUAAzEhW2p1eAsAAQT1AQAABBQAAABQSwECHgMKAAAAAADFffJcAAAAAAAAAAAAAAAACQAYAAAAAAAAABAA7UFkAQAAZG9jUHJvcHMvVVQFAAMxIVtqdXgLAAEE9QEAAAQUAAAAUEsBAh4DFAAAAAgAxX3yXBEW4wHXAAAASQEAABEAGAAAAAAAAQAAAKSBpwEAAGRvY1Byb3BzL2NvcmUueG1sVVQFAAMxIVtqdXgLAAEE9QEAAAQUAAAAUEsFBgAAAAAEAAQASAEAAMkCAAAAAA=="
        let fixture = try XCTUnwrap(Data(base64Encoded: fixtureBase64))
        let sourceURL = root.appendingPathComponent("integration.docx")
        try fixture.write(to: sourceURL)

        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let localURL = try LibraryPaths.bookURL(
            fileName: imported.localFileName,
            rootDirectoryURL: root
        )

        XCTAssertEqual(imported.sourceFormat, .docx)
        XCTAssertEqual(imported.title, "DOCX Integration")
        XCTAssertEqual(imported.author, "Jerreader Tests")
        XCTAssertEqual(imported.language, "ja-JP")
        XCTAssertEqual(localURL.pathExtension, "epub")
        XCTAssertTrue(FileManager.default.fileExists(atPath: localURL.path))
    }

    func testPDFImportCopiesDocumentAndCreatesCover() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("Reader PDF.pdf")
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        let data = renderer.pdfData { context in
            context.beginPage()
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: 22),
                .foregroundColor: UIColor.black,
            ]
            NSString(string: "Jerreader selectable PDF text")
                .draw(at: CGPoint(x: 28, y: 48), withAttributes: attributes)
        }
        try data.write(to: sourceURL)

        let service = EPUBImportService(rootDirectoryURL: root)
        let imported = try await service.importBook(
            from: sourceURL,
            existingFingerprints: []
        )
        let localURL = try LibraryPaths.bookURL(
            fileName: imported.localFileName,
            rootDirectoryURL: root
        )

        XCTAssertEqual(imported.sourceFormat, .pdf)
        XCTAssertEqual(localURL.pathExtension, "pdf")
        XCTAssertTrue(FileManager.default.fileExists(atPath: localURL.path))
        XCTAssertNotNil(imported.coverFileName)
        if let coverFileName = imported.coverFileName {
            let coverURL = try LibraryPaths.coverURL(
                fileName: coverFileName,
                rootDirectoryURL: root
            )
            XCTAssertTrue(FileManager.default.fileExists(atPath: coverURL.path))
        }
    }

    func testImportedPDFStartsTheReadiumPDFNavigator() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("navigator.pdf")
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        try renderer.pdfData { context in
            context.beginPage()
            NSString(string: "Selectable PDF page")
                .draw(at: CGPoint(x: 24, y: 44), withAttributes: [
                    .font: UIFont.systemFont(ofSize: 20),
                ])
        }.write(to: sourceURL)

        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let record = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            coverFileName: imported.coverFileName,
            fileFingerprint: imported.fileFingerprint
        )
        let publication = try await EPUBPublicationService(
            rootDirectoryURL: root
        ).open(book: record)
        var controller: PDFReaderViewController? = try PDFReaderViewController(
            publication: publication,
            initialLocation: nil,
            theme: .coolGray,
            bookLanguage: nil
        )

        controller?.loadViewIfNeeded()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller
        window.makeKeyAndVisible()

        var pdfView: PDFView?
        for _ in 0 ..< 80 {
            pdfView = controller?.view.flatMap(firstPDFView)
            if pdfView?.document?.pageCount == 1 {
                break
            }
            try await Task.sleep(for: .milliseconds(25))
        }

        XCTAssertEqual(controller?.isPaginated, true)
        XCTAssertEqual(controller?.supportsQuickSentenceTranslation, true)
        XCTAssertTrue(controller?.navigator.parent === controller)

        let resolvedPDFView = try XCTUnwrap(pdfView)
        let page = try XCTUnwrap(resolvedPDFView.document?.page(at: 0))
        let pageText = try XCTUnwrap(page.string as NSString?)
        let range = pageText.range(of: "Selectable PDF page")
        let nativeSelection = try XCTUnwrap(page.selection(for: range))
        resolvedPDFView.setCurrentSelection(nativeSelection, animate: false)

        var payload: ReaderSelectionPayload?
        controller?.onSelectedText = { payload = $0 }
        XCTAssertEqual(
            controller?.canPerformAction(
                #selector(PDFReaderViewController.translateCurrentSelection(_:)),
                withSender: nil
            ),
            true
        )
        controller?.translateCurrentSelection(nil)
        XCTAssertEqual(payload?.text, "Selectable PDF page")
        XCTAssertEqual(payload?.trigger, .preciseSelection)
        XCTAssertNotNil(payload?.annotationAnchorJSON)

        window.rootViewController = nil
        window.isHidden = true
        controller = nil
        publication.close()
    }

    func testVisionOCRRecognizesRenderedEnglishSentence() async throws {
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: 900, height: 360)
        )
        let image = renderer.image { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 900, height: 360))
            NSString(string: "OCR sentence for Jerreader.").draw(
                at: CGPoint(x: 54, y: 130),
                withAttributes: [
                    .font: UIFont.systemFont(ofSize: 58, weight: .medium),
                    .foregroundColor: UIColor.black,
                ]
            )
        }
        let cgImage = try XCTUnwrap(image.cgImage)

        let observations = try await PDFPageTextRecognizer.recognize(
            cgImage: cgImage,
            languages: ["en-US"]
        )
        let recognized = observations.map(\.text).joined(separator: " ").lowercased()

        XCTAssertTrue(recognized.contains("ocr sentence"), recognized)
        XCTAssertTrue(observations.allSatisfy { !$0.boundingBox.isEmpty })
    }

    func testImporterRejectsNonEPUBFile() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("txt")
        try Data("not an epub".utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        do {
            _ = try await EPUBImportService().importEPUB(
                from: url,
                existingFingerprints: []
            )
            XCTFail("Expected an unsupported format error")
        } catch {
            XCTAssertEqual(error as? BookImportError, .unsupportedFormat)
        }
    }

    func testImporterDetectsDuplicateBeforeParsing() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("epub")
        let data = Data("duplicate fixture".utf8)
        try data.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let fingerprint = SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()

        do {
            _ = try await EPUBImportService().importEPUB(
                from: url,
                existingFingerprints: [fingerprint]
            )
            XCTFail("Expected a duplicate book error")
        } catch {
            XCTAssertEqual(
                error as? DuplicateBookImportError,
                DuplicateBookImportError(fingerprint: fingerprint)
            )
        }
    }

    func testImportReadsSourceWithoutChangingItsModificationDate() async throws {
        let sourceRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let libraryRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: sourceRoot, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: libraryRoot, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.removeItem(at: sourceRoot)
            try? FileManager.default.removeItem(at: libraryRoot)
        }

        let sourceURL = sourceRoot.appendingPathComponent("read-only-source.txt")
        try Data("只读导入测试。".utf8).write(to: sourceURL)
        let originalDate = Date(timeIntervalSince1970: 946_684_800)
        try FileManager.default.setAttributes(
            [.modificationDate: originalDate],
            ofItemAtPath: sourceURL.path
        )

        _ = try await EPUBImportService(rootDirectoryURL: libraryRoot)
            .importBook(from: sourceURL, existingFingerprints: [])

        let attributes = try FileManager.default.attributesOfItem(atPath: sourceURL.path)
        let dateAfterImport = try XCTUnwrap(attributes[.modificationDate] as? Date)
        XCTAssertEqual(dateAfterImport.timeIntervalSince1970, originalDate.timeIntervalSince1970)
    }

    func testOpeningStoredBookIsReadOnlyAndPreservesModificationDate() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true
        )
        defer { try? FileManager.default.removeItem(at: root) }

        let sourceURL = root.appendingPathComponent("open-read-only.txt")
        try Data("打开不能修改书籍。\n\n第二个段落。".utf8).write(to: sourceURL)
        let imported = try await EPUBImportService(rootDirectoryURL: root)
            .importBook(from: sourceURL, existingFingerprints: [])
        let localURL = try LibraryPaths.bookURL(
            fileName: imported.localFileName,
            rootDirectoryURL: root
        )
        let originalData = try Data(contentsOf: localURL)
        let originalDate = Date(timeIntervalSince1970: 978_307_200)
        try FileManager.default.setAttributes(
            [
                .modificationDate: originalDate,
                .posixPermissions: 0o644,
            ],
            ofItemAtPath: localURL.path
        )
        let book = BookRecord(
            title: imported.title,
            author: imported.author,
            language: imported.language,
            sourceFormat: imported.sourceFormat.rawValue,
            localFileName: imported.localFileName,
            fileFingerprint: imported.fileFingerprint
        )

        let publicationService = EPUBPublicationService(rootDirectoryURL: root)
        let publication = try await publicationService.open(book: book)

        // Simulate a framework touching metadata after open() has returned.
        // The reader retains the service for the entire Navigator session and
        // reasserts the captured date during persistence and final close.
        try FileManager.default.setAttributes(
            [.modificationDate: Date(timeIntervalSince1970: 1_700_000_000)],
            ofItemAtPath: localURL.path
        )
        publicationService.reassertBookFileMetadata()
        publication.close()
        publicationService.finishBookFileAccess()

        let attributes = try FileManager.default.attributesOfItem(
            atPath: localURL.path
        )
        let dateAfterOpen = try XCTUnwrap(
            attributes[.modificationDate] as? Date
        )
        let permissions = try XCTUnwrap(
            attributes[.posixPermissions] as? NSNumber
        ).intValue
        XCTAssertEqual(
            dateAfterOpen.timeIntervalSince1970,
            originalDate.timeIntervalSince1970
        )
        XCTAssertEqual(permissions & 0o222, 0)
        XCTAssertEqual(try Data(contentsOf: localURL), originalData)
    }

    func testAppDoesNotRequestOpeningProviderDocumentsInPlace() {
        XCTAssertEqual(
            Bundle.main.object(forInfoDictionaryKey: "LSSupportsOpeningDocumentsInPlace") as? Bool,
            false
        )
    }

    func testIncomingDuplicateMatchesExistingShelfRecord() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("epub")
        try Data("existing shelf copy".utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let fingerprint = try await EPUBImportService()
            .fingerprintForIncomingDocument(at: url)
        let existingBook = BookRecord(
            title: "已导入的书",
            author: "作者",
            localFileName: "existing.epub",
            fileFingerprint: fingerprint
        )
        let unrelatedBook = BookRecord(
            title: "另一本书",
            author: "作者",
            localFileName: "unrelated.epub",
            fileFingerprint: "another-fingerprint"
        )

        let match = LibraryBookMatcher.matching(
            fingerprint: fingerprint,
            in: [unrelatedBook, existingBook]
        )

        XCTAssertTrue(match === existingBook)
        XCTAssertNil(
            LibraryBookMatcher.matching(
                fingerprint: "missing-fingerprint",
                in: [unrelatedBook, existingBook]
            )
        )
    }

    func testRemoveFilesDeletesBookAndCoverAndIsIdempotent() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }

        let directories = try LibraryPaths.prepareDirectories(rootDirectoryURL: root)
        let bookFileName = "book.epub"
        let coverFileName = "cover.jpg"
        let bookURL = directories.books.appendingPathComponent(bookFileName)
        let coverURL = directories.covers.appendingPathComponent(coverFileName)
        try Data("epub fixture".utf8).write(to: bookURL)
        try Data("cover fixture".utf8).write(to: coverURL)

        let service = EPUBImportService(rootDirectoryURL: root)
        try await service.removeFiles(
            localFileName: bookFileName,
            coverFileName: coverFileName
        )

        XCTAssertFalse(FileManager.default.fileExists(atPath: bookURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: coverURL.path))

        try await service.removeFiles(
            localFileName: bookFileName,
            coverFileName: coverFileName
        )
    }
}

@MainActor
private func firstPDFView(in view: UIView) -> PDFView? {
    if let pdfView = view as? PDFView {
        return pdfView
    }
    for subview in view.subviews {
        if let pdfView = firstPDFView(in: subview) {
            return pdfView
        }
    }
    return nil
}

@MainActor
private func firstWebView(in root: UIView?) -> WKWebView? {
    guard let root else { return nil }
    if let webView = root as? WKWebView {
        return webView
    }
    for subview in root.subviews {
        if let webView = firstWebView(in: subview) {
            return webView
        }
    }
    return nil
}

@MainActor
private func allWebViews(in root: UIView?) -> [WKWebView] {
    guard let root else { return [] }
    var result: [WKWebView] = []
    if let webView = root as? WKWebView {
        result.append(webView)
    }
    for subview in root.subviews {
        result.append(contentsOf: allWebViews(in: subview))
    }
    return result
}

/// Builds a Japanese EPUB that pins its own vertical writing mode, the way
/// real EBPAJ publications do. This is the shape Readium preferences alone can
/// never turn horizontal, so the fixture has to reproduce it exactly:
/// `<html class="vrtl">` plus a publisher rule using the vendor-prefixed
/// properties, and an `rtl` spine progression.
@discardableResult
func writeVerticalJapaneseEPUB(
    to destinationURL: URL,
    title: String = "縦書きの本"
) async throws -> URL {
    let identifier = "urn:uuid:\(UUID().uuidString.lowercased())"
    let modified = ISO8601DateFormatter().string(from: Date())
    let files: [(path: String, data: Data, compressed: Bool)] = [
        ("mimetype", Data("application/epub+zip".utf8), false),
        ("META-INF/container.xml", Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.utf8), true),
        ("OEBPS/content.opf", Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="ja">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:identifier id="book-id">\(identifier)</dc:identifier>
            <dc:title>\(title)</dc:title>
            <dc:creator>テスト</dc:creator>
            <dc:language>ja</dc:language>
            <meta property="dcterms:modified">\(modified)</meta>
          </metadata>
          <manifest>
            <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
            <item id="style" href="style.css" media-type="text/css"/>
          </manifest>
          <spine page-progression-direction="rtl">
            <itemref idref="chapter"/>
          </spine>
        </package>
        """.utf8), true),
        ("OEBPS/nav.xhtml", Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" lang="ja" xml:lang="ja">
          <head><title>目次</title></head>
          <body><nav epub:type="toc" xmlns:epub="http://www.idpf.org/2007/ops"><ol><li><a href="chapter.xhtml">\(title)</a></li></ol></nav></body>
        </html>
        """.utf8), true),
        ("OEBPS/chapter.xhtml", Data("""
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="ja" xml:lang="ja" class="vrtl">
          <head>
            <meta charset="utf-8"/>
            <title>\(title)</title>
            <link rel="stylesheet" type="text/css" href="style.css"/>
          </head>
          <body class="vrtl">
            <p id="paragraph">吾輩は猫である。名前はまだ無い。どこで生れたか頓と見当がつかぬ。</p>
            <p>何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。</p>
          </body>
        </html>
        """.utf8), true),
        ("OEBPS/style.css", Data("""
        html { height: 100%; }
        .vrtl {
          -webkit-writing-mode: vertical-rl;
          -epub-writing-mode: vertical-rl;
          writing-mode: vertical-rl;
        }
        """.utf8), true),
    ]

    let archive = try await Archive(url: destinationURL, accessMode: .create)
    for file in files {
        let data = file.data
        try await archive.addEntry(
            with: file.path,
            type: .file,
            uncompressedSize: Int64(data.count),
            compressionMethod: file.compressed ? .deflate : .none
        ) { position, size in
            let start = min(max(Int(position), 0), data.count)
            let end = min(start + size, data.count)
            return data.subdata(in: start ..< end)
        }
    }
    return destinationURL
}
