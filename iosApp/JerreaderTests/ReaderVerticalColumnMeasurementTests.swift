import WebKit
import XCTest
@testable import JerreaderCore
@testable import JerreaderUnified

/// Vertical page turns hold one column back so no column is split across the
/// boundary. The rule that decides how far to move is Kotlin
/// (`ReaderVerticalPageStep`, tested in `core`); the number it works from comes
/// from measuring the rendered document, and that half is what these tests
/// cover — in a real WebKit layout, because the whole point is that WebKit,
/// not the app, decides how wide a column is.
@MainActor
final class ReaderVerticalColumnMeasurementTests: XCTestCase {

    func testPagingPolicyKeepsPaginatedChoiceAuthoritativeAcrossReadiumReloads() {
        XCTAssertEqual(
            EPUBPagingPolicy.resolve(
                readingMode: .paginated,
                rendersVerticalText: false,
                voiceOverRunning: false
            ),
            EPUBPagingPolicy(
                isPaginated: true,
                usesNativePaging: true,
                usesVerticalSnapping: false
            )
        )
        XCTAssertEqual(
            EPUBPagingPolicy.resolve(
                readingMode: .paginated,
                rendersVerticalText: true,
                voiceOverRunning: false
            ),
            EPUBPagingPolicy(
                isPaginated: true,
                usesNativePaging: false,
                usesVerticalSnapping: true
            )
        )
    }

    func testPagingPolicyLeavesVoiceOverInContinuousScrollMode() {
        XCTAssertEqual(
            EPUBPagingPolicy.resolve(
                readingMode: .paginated,
                rendersVerticalText: true,
                voiceOverRunning: true
            ),
            EPUBPagingPolicy(
                isPaginated: false,
                usesNativePaging: false,
                usesVerticalSnapping: false
            )
        )
    }

    func testVerticalSnapWinsAfterForwardedDelegateChangesProjectedOffset() {
        final class ForwardingDelegate: NSObject, UIScrollViewDelegate {
            func scrollViewWillEndDragging(
                _ scrollView: UIScrollView,
                withVelocity velocity: CGPoint,
                targetContentOffset: UnsafeMutablePointer<CGPoint>
            ) {
                targetContentOffset.pointee.x = 1_234
            }
        }

        let scrollView = UIScrollView(frame: CGRect(x: 0, y: 0, width: 390, height: 700))
        scrollView.contentSize = CGSize(width: 2_000, height: 700)
        scrollView.contentOffset = CGPoint(x: 780, y: 0)
        let forwarding = ForwardingDelegate()
        let proxy = EPUBVerticalPagingScrollDelegate()
        proxy.forwarding = forwarding
        proxy.pageTarget = { _, _, _ in 390 }
        proxy.scrollViewWillBeginDragging(scrollView)

        var target = CGPoint(x: 620, y: 0)
        withUnsafeMutablePointer(to: &target) { pointer in
            proxy.scrollViewWillEndDragging(
                scrollView,
                withVelocity: CGPoint(x: -1, y: 0),
                targetContentOffset: pointer
            )
        }

        XCTAssertEqual(target.x, 390, "the custom whole-page target must be final")
    }

    /// Renders `html` at the given size and returns what the measurement script
    /// reports, in CSS pixels.
    private func measureColumnAdvance(
        html: String,
        width: CGFloat,
        height: CGFloat
    ) async throws -> Double? {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: width, height: height))
        let host = UIViewController()
        host.view.frame = webView.bounds
        host.view.backgroundColor = .white
        host.view.addSubview(webView)
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = webView.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        webView.loadHTMLString(html, baseURL: nil)
        var didLoad = false
        for _ in 0 ..< 400 {
            if try await webView.evaluateJavaScript(
                "document.readyState === 'complete' && document.body !== null"
            ) as? Bool == true {
                didLoad = true
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(didLoad, "The WebKit document must finish loading before measurement")
        guard didLoad else { return nil }
        window.layoutIfNeeded()
        host.view.layoutIfNeeded()
        webView.layoutIfNeeded()

        // "Loaded" is not "laid out": WebKit reports `readyState === 'complete'`
        // before it has line boxes, and the script correctly answers nothing
        // until it does. The app handles this by re-measuring whenever the
        // scroll view's content size changes; here we simply wait, up to two
        // seconds, which is also what makes "measures nothing" mean *never*
        // rather than *not yet*.
        for _ in 0 ..< 200 {
            let raw = try await webView.evaluateJavaScript(
                EPUBReaderViewController.columnAdvanceScript
            )
            if let value = (raw as? NSNumber)?.doubleValue { return value }
            try await Task.sleep(for: .milliseconds(10))
        }
        return nil
    }

    private func verticalDocument(
        fontSize: Int,
        lineHeight: String = "1.8",
        body: String
    ) -> String {
        """
        <!doctype html><html lang="ja"><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body {
            margin: 0;
            padding: 0;
            -webkit-writing-mode: vertical-rl;
            writing-mode: vertical-rl;
            font-size: \(fontSize)px;
            line-height: \(lineHeight);
            font-family: "Hiragino Mincho ProN", serif;
          }
        </style>
        </head><body>\(body)</body></html>
        """
    }

    private var longJapaneseParagraph: String {
        let sentence = "廊下には誰もいなかった。彼は扉に手をかけたが、遠くの声を聞いて立ち止まった。"
        return "<p>" + String(repeating: sentence, count: 40) + "</p>"
    }

    /// The measurement must land on the column-to-column distance — font size
    /// times line height — and not on the ink box of the glyphs, which in
    /// WebKit is only about the font size and would hold back far too little.
    func testMeasuredAdvanceMatchesTheLineBoxOfAVerticalDocument() async throws {
        let fontSize = 20
        let lineHeight = 1.8
        let measured = try await measureColumnAdvance(
            html: verticalDocument(fontSize: fontSize, lineHeight: "\(lineHeight)", body: longJapaneseParagraph),
            width: 390,
            height: 700
        )
        let advance = try XCTUnwrap(measured, "a full vertical page must measure something")
        XCTAssertEqual(
            advance,
            Double(fontSize) * lineHeight,
            accuracy: 2.0,
            "expected the line advance (\(Double(fontSize) * lineHeight)), got \(advance) — "
                + "a value near \(fontSize) means the ink box is being measured instead"
        )
    }

    /// A larger font makes wider columns. If the measurement did not track the
    /// font it would be a constant, and the cache invalidation on preference
    /// changes would be pointless.
    func testMeasuredAdvanceGrowsWithFontSize() async throws {
        let rawSmall = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 16, body: longJapaneseParagraph),
            width: 390,
            height: 700
        )
        let small = try XCTUnwrap(rawSmall)
        let rawLarge = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 28, body: longJapaneseParagraph),
            width: 390,
            height: 700
        )
        let large = try XCTUnwrap(rawLarge)
        XCTAssertGreaterThan(large, small * 1.4, "28px columns must be clearly wider than 16px ones")
    }

    /// Ruby annotations render in their own, much narrower boxes. Including
    /// them would drag the answer below a real column and make the overlap too
    /// small to do its job.
    func testRubyAnnotationsDoNotShrinkTheMeasurement() async throws {
        let plain = "<p>" + String(repeating: "廊下には誰もいなかった。", count: 60) + "</p>"
        let ruby = "<p>" + String(
            repeating: "<ruby>廊下<rt>ろうか</rt></ruby>には<ruby>誰<rt>だれ</rt></ruby>もいなかった。",
            count: 60
        ) + "</p>"

        let rawWithoutruby = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 20, body: plain),
            width: 390,
            height: 700
        )
        let withoutRuby = try XCTUnwrap(rawWithoutruby)
        let rawWithruby = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 20, body: ruby),
            width: 390,
            height: 700
        )
        let withRuby = try XCTUnwrap(rawWithruby)
        // Ruby widens the line box a little because the annotation sits beside
        // the base text; what it must never do is halve it.
        XCTAssertGreaterThanOrEqual(withRuby, withoutRuby * 0.9, "ruby must not drag the median down")
    }

    /// A horizontal document has no vertical line boxes to find. Returning
    /// nothing is the correct answer — the caller then steps a full viewport,
    /// which is what horizontal reading wants anyway.
    func testAHorizontalDocumentMeasuresNothing() async throws {
        let measured = try await measureColumnAdvance(
            html: """
            <!doctype html><html lang="en"><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html, body { margin: 0; font-size: 18px; }</style>
            </head><body><p>\(String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 40))</p></body></html>
            """,
            width: 390,
            height: 700
        )
        XCTAssertNil(measured, "horizontal text has no vertical line boxes")
    }

    /// An empty page measures nothing rather than zero, so the caller can leave
    /// the slot unmeasured and retry after the next layout pass.
    func testAnEmptyDocumentMeasuresNothing() async throws {
        let measured = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 20, body: "<p>   </p>"),
            width: 390,
            height: 700
        )
        XCTAssertNil(measured)
    }

    /// The end-to-end property the user asked for: on a real rendered page, one
    /// page turn must leave at least a full column visible on both sides of the
    /// boundary.
    func testARealVerticalPageTurnKeepsOneColumnOnBothPages() async throws {
        let viewportWidth: Double = 390
        let rawAdvance = try await measureColumnAdvance(
            html: verticalDocument(fontSize: 20, body: longJapaneseParagraph),
            width: CGFloat(viewportWidth),
            height: 700
        )
        let advance = try XCTUnwrap(rawAdvance)

        let step = ReaderVerticalPageStep.shared.advance(
            viewportWidth: viewportWidth,
            columnAdvance: KotlinDouble(value: advance)
        )
        XCTAssertLessThan(step, viewportWidth, "the step must hold a column back")
        XCTAssertTrue(
            ReaderVerticalPageStep.shared.keepsEveryColumnWhole(
                viewportWidth: viewportWidth,
                columnAdvance: advance,
                step: step
            ),
            "step \(step) over viewport \(viewportWidth) splits a \(advance)pt column"
        )

        // Stated as columns for legibility: page one shows the columns starting
        // in [0, step); page two starts at `step`, so the column that begins at
        // the last multiple of `advance` below `step` is whole on both.
        let overlap = viewportWidth - step
        XCTAssertGreaterThanOrEqual(overlap, advance - 0.001, "overlap \(overlap) is under one column")
    }
}
