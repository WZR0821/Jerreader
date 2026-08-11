import UIKit
@preconcurrency import WebKit
import JerreaderCore

struct ReaderSelectionVisualPalette {
    let fill: UIColor
    let stroke: UIColor
    let spokenFill: UIColor

    static let light = ReaderSelectionVisualStyle.palette(
        theme: .light,
        customBackgroundHex: "",
        customSelectionColorHex: ""
    )

    var fillCSS: String { fill.readerCSSRGBA }
    var strokeCSS: String { stroke.readerCSSRGBA }
    var spokenFillCSS: String { spokenFill.readerCSSRGBA }
}

enum ReaderSelectionVisualStyle {
    // Kept as compatibility shorthands for existing geometry/view tests.
    static let fill = ReaderSelectionVisualPalette.light.fill
    static let stroke = ReaderSelectionVisualPalette.light.stroke
    static let spokenFill = ReaderSelectionVisualPalette.light.spokenFill

    /// Delegates to the shared palette so both apps derive the selection
    /// colour from the same rules — including the contrast nudge that keeps a
    /// custom accent visible once the translucent fill is composited over the
    /// page. The Swift implementation this replaced had drifted to an older
    /// luminance constant and produced a different colour for the same
    /// settings.
    static func palette(
        theme: ReaderThemeChoice,
        customBackgroundHex: String,
        customSelectionColorHex: String
    ) -> ReaderSelectionVisualPalette {
        let shared = JerreaderCore.ReaderSelectionVisualStyle.shared.palette(
            appearance: ReaderAppearance.from(
                theme: theme,
                customBackgroundHex: customBackgroundHex,
                customSelectionColorHex: customSelectionColorHex
            )
        )
        return ReaderSelectionVisualPalette(
            fill: UIColor(readerArgb: shared.fillArgb),
            stroke: UIColor(readerArgb: shared.strokeArgb),
            spokenFill: UIColor(readerArgb: shared.spokenFillArgb)
        )
    }

}

private extension UIColor {
    var readerRGBA: (red: CGFloat, green: CGFloat, blue: CGFloat, alpha: CGFloat) {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        return (red, green, blue, alpha)
    }

    var readerCSSRGBA: String {
        let value = readerRGBA
        return String(
            format: "rgba(%d,%d,%d,%.3f)",
            Int((value.red * 255).rounded()),
            Int((value.green * 255).rounded()),
            Int((value.blue * 255).rounded()),
            value.alpha
        )
    }

    /// The shared palette hands colours back as 0xAARRGGBB in a Kotlin `Long`.
    convenience init(readerArgb argb: Int64) {
        self.init(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
}

enum ReaderSpeechHighlightGeometry {
    /// Delegates to the shared geometry, which slices the painted tiles down to
    /// the span currently being spoken. Android had no equivalent at all until
    /// this moved into the shared module, so read-aloud there highlighted the
    /// whole sentence at once.
    ///
    /// The cut runs along the line axis, so a vertical column is cut downwards
    /// rather than across. Passing the writing mode explicitly beats the old
    /// aspect-ratio guess for a short vertical run of one or two characters.
    static func rectangles(
        in sourceRectangles: [CGRect],
        highlight: ReaderSpeechHighlight?,
        vertical: Bool = false
    ) -> [CGRect] {
        guard let shared = sharedSpeechHighlight(
            lowerBound: highlight?.lowerBound,
            upperBound: highlight?.upperBound
        ) else {
            return []
        }
        return JerreaderCore.ReaderSpeechHighlightGeometry.shared.rectangles(
            sourceRectangles: sourceRectangles.map(ReaderRect.from),
            highlight: shared,
            mode: ReaderWritingMode.of(vertical: vertical)
        ).map(\.cgRect)
    }
}

/// Arbitrates the release event of a native long press against the reader's
/// independent tap-to-translate gesture.
///
/// WebKit/Readium can report a normal tap immediately after finishing a text
/// selection. Without a short monotonic-time gate, that trailing callback
/// clears the native selection and replaces it with a whole-sentence tap
/// translation. This small state object keeps the gesture decision explicit
/// and unit-testable instead of scattering timing flags through the reader.
final class EPUBSelectionGestureGate {
    static let defaultSuppressionDuration: TimeInterval = 1.5

    /// Wraps the shared gate, converting the reader's seconds to the shared
    /// module's milliseconds. Android had the same trailing-tap bug and no gate
    /// at all, which is why the rule moved into the shared module.
    ///
    /// A class, not a struct: the state lives in the Kotlin object, so a struct
    /// would advertise value semantics it cannot deliver — two copies would
    /// quietly share one suppression window.
    private let gate: ReaderSelectionGestureGate

    /// Spelled as two initialisers rather than one with a default argument:
    /// Swift 6's SIL generation crashes emitting the default-argument thunk for
    /// a class initialiser that reads a static of its own type under complete
    /// strict concurrency.
    init(suppressionDuration: TimeInterval) {
        gate = ReaderSelectionGestureGate(
            suppressionMillis: Int64(max(0, suppressionDuration) * 1000)
        )
    }

    convenience init() {
        self.init(suppressionDuration: EPUBSelectionGestureGate.defaultSuppressionDuration)
    }

    var suppressTapUntilUptime: TimeInterval? {
        gate.suppressTapUntilUptimeMillis.map { TimeInterval(truncating: $0) / 1000 }
    }

    func noteNativeSelection(at uptime: TimeInterval) {
        guard uptime.isFinite else { return }
        gate.noteNativeSelection(uptimeMillis: Int64(uptime * 1000))
    }

    func shouldSuppressTap(at uptime: TimeInterval) -> Bool {
        guard uptime.isFinite else { return false }
        return gate.shouldSuppressTap(uptimeMillis: Int64(uptime * 1000))
    }

    func reset() {
        gate.reset()
    }
}

/// Keeps a stationary native long press and a horizontal page swipe mutually
/// exclusive. Other recognizers (notably WebKit's own selection recognizer)
/// still cooperate with the non-blocking fallback observer.
enum EPUBLongPressPageTurnPolicy {
    static func allowsSimultaneousRecognition(
        otherGestureIsPagePan: Bool
    ) -> Bool {
        !otherGestureIsPagePan
    }
}

/// A semantic snapshot of WebKit's native selection.
///
/// WebKit is allowed to keep its own editing range and handles. The reader only
/// consumes an annotation-free representation of that range, so Japanese ruby
/// readings never become the text sent to translation.
struct EPUBSelectionSnapshot {
    let rawText: String
    let baseText: String
    let before: String?
    let after: String?
    let localRects: [CGRect]
    let recoveredFromRuby: Bool
    let anchorRole: String
    let focusRole: String

    init?(javaScriptValue value: Any) {
        guard let dictionary = value as? [String: Any],
              let baseText = dictionary["normalizedText"] as? String,
              !baseText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            return nil
        }

        let rectangles = (dictionary["rects"] as? [Any] ?? [])
            .compactMap(Self.rect(from:))
        guard !rectangles.isEmpty else { return nil }

        rawText = dictionary["rawText"] as? String ?? ""
        self.baseText = baseText
        before = dictionary["before"] as? String
        after = dictionary["after"] as? String
        localRects = rectangles
        recoveredFromRuby = dictionary["recoveredFromRuby"] as? Bool ?? false
        anchorRole = dictionary["anchorRole"] as? String ?? "unknown"
        focusRole = dictionary["focusRole"] as? String ?? "unknown"
    }

    private static func rect(from value: Any) -> CGRect? {
        guard let json = value as? [String: Any],
              let x = json["x"] as? NSNumber,
              let y = json["y"] as? NSNumber,
              let width = json["width"] as? NSNumber,
              let height = json["height"] as? NSNumber
        else {
            return nil
        }

        let rect = CGRect(
            x: CGFloat(truncating: x),
            y: CGFloat(truncating: y),
            width: CGFloat(truncating: width),
            height: CGFloat(truncating: height)
        ).standardized
        guard rect.minX.isFinite,
              rect.minY.isFinite,
              rect.width.isFinite,
              rect.height.isFinite,
              rect.width > 0.5,
              rect.height > 0.5
        else {
            return nil
        }
        return rect
    }
}

/// Owns the visual selection independently from the EPUB DOM.
///
/// Keeping this as a non-interactive UIKit sibling avoids two WebKit failure
/// modes at once: rewriting a ruby Range can make the native highlight vanish,
/// while DOM overlays can be displaced by Readium's column transforms.
@MainActor
final class EPUBSelectionBridge {
    private let highlightView = EPUBSelectionHighlightView()

    var displayedRects: [CGRect] {
        highlightView.displayedRects
    }

    func attach(to containerView: UIView) {
        guard highlightView.superview == nil else { return }
        highlightView.translatesAutoresizingMaskIntoConstraints = false
        containerView.addSubview(highlightView)
        NSLayoutConstraint.activate([
            highlightView.leadingAnchor.constraint(equalTo: containerView.leadingAnchor),
            highlightView.trailingAnchor.constraint(equalTo: containerView.trailingAnchor),
            highlightView.topAnchor.constraint(equalTo: containerView.topAnchor),
            highlightView.bottomAnchor.constraint(equalTo: containerView.bottomAnchor)
        ])
    }

    func updateVisualStyle(
        theme: ReaderThemeChoice,
        customBackgroundHex: String,
        customSelectionColorHex: String
    ) {
        highlightView.updatePalette(
            ReaderSelectionVisualStyle.palette(
                theme: theme,
                customBackgroundHex: customBackgroundHex,
                customSelectionColorHex: customSelectionColorHex
            )
        )
    }

    /// The writing mode the *reader* configured, which decides the axis the
    /// merger groups lines along. It comes from the host rather than from the
    /// page because some web views report `-epub-writing-mode: vertical-rl` as
    /// `horizontal-tb` through `getComputedStyle`.
    func updateWritingMode(vertical: Bool) {
        highlightView.updateWritingMode(vertical: vertical)
    }

    /// Displays base-text rectangles and returns their union in window space.
    @discardableResult
    func display(
        snapshot: EPUBSelectionSnapshot,
        from webView: WKWebView
    ) -> CGRect? {
        guard highlightView.superview != nil else { return nil }
        let visibleBounds = highlightView.bounds.insetBy(dx: -2, dy: -2)
        let converted = snapshot.localRects.compactMap { localRect -> CGRect? in
            // DOM client rectangles are viewport-relative. UIView conversion,
            // however, expects coordinates in the WKWebView's current bounds
            // space. Readium pages by moving the web view's bounds origin, so
            // converting the raw DOM rectangle can place a valid selection a
            // whole column off-screen. Rebase it onto the visible bounds first.
            let rect = Self.convertViewportRect(
                localRect,
                from: webView,
                to: highlightView
            )
            guard rect.minX.isFinite,
                  rect.minY.isFinite,
                  rect.width.isFinite,
                  rect.height.isFinite,
                  rect.intersects(visibleBounds)
            else {
                return nil
            }
            return rect.intersection(visibleBounds)
        }
        guard !converted.isEmpty else {
            clear()
            return nil
        }

        highlightView.display(converted, selectedText: snapshot.baseText)
        let union = converted.dropFirst().reduce(converted[0]) { $0.union($1) }
        return highlightView.convert(union, to: nil).standardized
    }

    static func convertViewportRect(
        _ rect: CGRect,
        from webView: WKWebView,
        to destinationView: UIView
    ) -> CGRect {
        // Readium's publication-preserving vertical layout keeps the WKWebView
        // full-screen and expresses the top safe-area inset as a negative
        // UIScrollView content offset. DOM client rectangles do not include
        // that UIKit-only displacement. Horizontal reflow instead positions
        // the WKWebView itself below the inset, so its content offset is zero.
        // Compensating only a negative vertical offset handles both layouts
        // without double-counting the safe area.
        let verticalInsetCompensation = max(
            0,
            -webView.scrollView.contentOffset.y
        )
        let boundsRect = rect.offsetBy(
            dx: webView.bounds.minX,
            dy: webView.bounds.minY + verticalInsetCompensation
        )
        return webView.convert(boundsRect, to: destinationView).standardized
    }

    /// Converts a UIKit point in WKWebView bounds coordinates into the DOM
    /// client-coordinate space used by caretRangeFromPoint/elementsFromPoint.
    /// This is the inverse of convertViewportRect for the viewport origin.
    static func domViewportPoint(_ point: CGPoint, in webView: WKWebView) -> CGPoint {
        let verticalInsetCompensation = max(
            0,
            -webView.scrollView.contentOffset.y
        )
        return CGPoint(
            x: point.x - webView.bounds.minX,
            y: point.y - webView.bounds.minY - verticalInsetCompensation
        )
    }

    func clear() {
        highlightView.clear()
    }

    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?) {
        highlightView.updateSpeechHighlight(highlight)
    }
}

@MainActor
final class EPUBSelectionHighlightView: UIView {
    private(set) var displayedRects: [CGRect] = []
    /// The unmerged input behind `displayedRects`, kept so a writing-mode change
    /// can re-merge the tiles that are already on screen. Merging groups
    /// fragments along the line axis, so tiles merged as horizontal text stay
    /// wrong for the rest of their life on screen otherwise.
    private var sourceRects: [CGRect] = []
    private var speechHighlight: ReaderSpeechHighlight?
    private var palette = ReaderSelectionVisualPalette.light
    private var verticalText = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
        isHidden = true
        contentMode = .redraw
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test") {
            accessibilityElementsHidden = false
            isAccessibilityElement = true
            accessibilityIdentifier = "epub-base-selection-highlight"
            accessibilityLabel = "正文选区高亮"
        }
#endif
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func display(_ rects: [CGRect], selectedText: String) {
        sourceRects = rects
        displayedRects = Self.merged(rects, vertical: verticalText)
        isHidden = displayedRects.isEmpty
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test") {
            accessibilityValue = displayedRects.isEmpty ? nil : selectedText
            if let first = displayedRects.first {
                let union = displayedRects.dropFirst().reduce(first) {
                    $0.union($1)
                }
                accessibilityFrame = convert(union, to: nil)
            }
        } else {
            accessibilityValue = nil
        }
#endif
        setNeedsDisplay()
    }

    func clear() {
        guard !displayedRects.isEmpty || !isHidden else { return }
        displayedRects.removeAll(keepingCapacity: true)
        sourceRects.removeAll(keepingCapacity: true)
        speechHighlight = nil
        isHidden = true
        accessibilityValue = nil
#if DEBUG
        accessibilityFrame = .null
#endif
        setNeedsDisplay()
    }

    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?) {
        guard speechHighlight != highlight else { return }
        speechHighlight = highlight
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test") {
            accessibilityIdentifier = highlight == nil
                ? "epub-base-selection-highlight"
                : "epub-speech-selection-highlight"
        }
#endif
        setNeedsDisplay()
    }

    func updatePalette(_ palette: ReaderSelectionVisualPalette) {
        self.palette = palette
        setNeedsDisplay()
    }

    func updateWritingMode(vertical: Bool) {
        guard verticalText != vertical else { return }
        verticalText = vertical
        // Switching 排版方向 does not clear the selection: Readium reloads the
        // spine item and only then is the selection dropped. Until that lands
        // the tiles stay on screen, and they were grouped along the *previous*
        // line axis — one tile per column becomes one tile per row. Re-merging
        // the rectangles they were built from keeps the painted tiles and the
        // speech slicing, which already reads the new mode, on the same axis.
        if !sourceRects.isEmpty {
            displayedRects = Self.merged(sourceRects, vertical: verticalText)
            isHidden = displayedRects.isEmpty
        }
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let context = UIGraphicsGetCurrentContext() else { return }
        context.saveGState()
        defer { context.restoreGState() }

        // Stroke width, outset and corner radius come from the shared metrics
        // so the same sentence is painted with the same weight on both
        // platforms. Android had drifted to a 1px stroke and a flat 4px corner.
        let outset = CGFloat(ReaderSelectionHighlightMetrics.shared.OUTSET)
        let strokeWidth = CGFloat(ReaderSelectionHighlightMetrics.shared.STROKE_WIDTH)

        func path(for tile: CGRect) -> UIBezierPath {
            UIBezierPath(
                roundedRect: tile.insetBy(dx: -outset, dy: -outset),
                cornerRadius: CGFloat(
                    ReaderSelectionHighlightMetrics.shared
                        .cornerRadius(tileHeight: Double(tile.height))
                )
            )
        }

        palette.fill.setFill()
        palette.stroke.setStroke()
        context.setLineWidth(strokeWidth)
        for selectionRect in displayedRects {
            let tile = path(for: selectionRect)
            tile.fill()
            tile.stroke()
        }

        palette.spokenFill.setFill()
        for speechRect in ReaderSpeechHighlightGeometry.rectangles(
            in: displayedRects,
            highlight: speechHighlight,
            vertical: verticalText
        ) {
            path(for: speechRect).fill()
        }
    }

    /// Delegates to the shared merger: one tile per contiguous run per line,
    /// so no pixel is ever filled or outlined twice.
    ///
    /// The Swift implementation this replaced assumed horizontal lines — it
    /// grouped by `midY` — so a Japanese vertical page had its merge axis
    /// rotated 90° and produced one tall tile per column instead of one per
    /// line. The shared merger flips the axis with the writing mode, and also
    /// keeps the containment pass that drops a ruby box covering its own kanji.
    private static func merged(_ rects: [CGRect], vertical: Bool) -> [CGRect] {
        ReaderSelectionRectMerger.shared.merge(
            rects: rects.map(ReaderRect.from),
            mode: ReaderWritingMode.of(vertical: vertical)
        ).map(\.cgRect)
    }
}
