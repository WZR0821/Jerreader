import UIKit
@preconcurrency import WebKit

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

    static func palette(
        theme: ReaderThemeChoice,
        customBackgroundHex: String,
        customSelectionColorHex: String
    ) -> ReaderSelectionVisualPalette {
        let customBackground = ReaderCustomBackground.uiColor(
            hex: customBackgroundHex
        )
        let background = customBackground ?? defaultBackground(for: theme)
        let chosenAccent = ReaderCustomBackground.uiColor(
            hex: customSelectionColorHex
        )
        let defaultAccent: UIColor = switch theme {
        case .light:
            UIColor(red: 0.25, green: 0.57, blue: 0.82, alpha: 1)
        case .sepia:
            UIColor(red: 0.79, green: 0.47, blue: 0.13, alpha: 1)
        case .coolGray:
            UIColor(red: 0.12, green: 0.52, blue: 0.62, alpha: 1)
        case .dark:
            UIColor(red: 0.32, green: 0.70, blue: 0.98, alpha: 1)
        }
        var accent = chosenAccent
            ?? (customBackground == nil
                ? defaultAccent
                : background.readerRelativeLuminance < 0.38
                    ? UIColor(red: 0.34, green: 0.74, blue: 1, alpha: 1)
                    : UIColor(red: 0.19, green: 0.49, blue: 0.76, alpha: 1))

        let darkBackground = background.readerRelativeLuminance < 0.38
        var fillAlpha: CGFloat = darkBackground ? 0.42 : 0.30
        let maximumAlpha: CGFloat = darkBackground ? 0.62 : 0.55
        let readablePole: UIColor = darkBackground ? .white : .black

        // Judge the color the user actually sees after the translucent fill is
        // composited over the page, not the opaque accent.
        //
        // The floor used to be 1.28, which every theme's own accent cleared on
        // its own background without any adjustment at all. That sounds fine
        // until you look at it: teal at 30% over the cool-grey page, or amber
        // over sepia, differs from the paper so little that the selection color
        // reads as not working.
        //
        // Reach for opacity before hue. Mixing towards black or white is what
        // makes a deliberately chosen custom color arrive as something else — a
        // vivid red showing up as muddy brick. More alpha makes the same color
        // more present without changing what it is.
        for _ in 0 ..< 12 {
            let visibleFill = accent.withAlphaComponent(fillAlpha)
                .readerComposited(over: background)
            if contrastRatio(visibleFill, background) >= 1.55 { break }
            if fillAlpha < maximumAlpha {
                fillAlpha = min(fillAlpha + 0.05, maximumAlpha)
            } else {
                accent = accent.readerMixed(with: readablePole, amount: 0.18)
            }
        }

        let stroke = accent.readerMixed(
            with: darkBackground ? .white : .black,
            amount: darkBackground ? 0.26 : 0.24
        ).withAlphaComponent(darkBackground ? 0.82 : 0.64)
        return ReaderSelectionVisualPalette(
            fill: accent.withAlphaComponent(fillAlpha),
            stroke: stroke,
            spokenFill: accent.withAlphaComponent(min(fillAlpha + 0.28, 0.85))
        )
    }

    private static func defaultBackground(
        for theme: ReaderThemeChoice
    ) -> UIColor {
        switch theme {
        case .light: return .white
        case .sepia:
            return UIColor(red: 0.98, green: 0.95, blue: 0.88, alpha: 1)
        case .coolGray:
            return UIColor(red: 0.93, green: 0.96, blue: 0.98, alpha: 1)
        case .dark:
            return UIColor(red: 0.08, green: 0.10, blue: 0.13, alpha: 1)
        }
    }

    private static func contrastRatio(
        _ first: UIColor,
        _ second: UIColor
    ) -> CGFloat {
        let lighter = max(
            first.readerRelativeLuminance,
            second.readerRelativeLuminance
        )
        let darker = min(
            first.readerRelativeLuminance,
            second.readerRelativeLuminance
        )
        return (lighter + 0.05) / (darker + 0.05)
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

    var readerRelativeLuminance: CGFloat {
        func linear(_ component: CGFloat) -> CGFloat {
            component <= 0.04045
                ? component / 12.92
                : pow((component + 0.055) / 1.055, 2.4)
        }
        let value = readerRGBA
        return 0.2126 * linear(value.red)
            + 0.7152 * linear(value.green)
            + 0.0722 * linear(value.blue)
    }

    func readerMixed(with other: UIColor, amount: CGFloat) -> UIColor {
        let ratio = min(max(amount, 0), 1)
        let first = readerRGBA
        let second = other.readerRGBA
        return UIColor(
            red: first.red + (second.red - first.red) * ratio,
            green: first.green + (second.green - first.green) * ratio,
            blue: first.blue + (second.blue - first.blue) * ratio,
            alpha: first.alpha + (second.alpha - first.alpha) * ratio
        )
    }

    func readerComposited(over background: UIColor) -> UIColor {
        let foreground = readerRGBA
        let base = background.readerRGBA
        let alpha = foreground.alpha + base.alpha * (1 - foreground.alpha)
        guard alpha > 0 else { return .clear }
        return UIColor(
            red: (foreground.red * foreground.alpha
                + base.red * base.alpha * (1 - foreground.alpha)) / alpha,
            green: (foreground.green * foreground.alpha
                + base.green * base.alpha * (1 - foreground.alpha)) / alpha,
            blue: (foreground.blue * foreground.alpha
                + base.blue * base.alpha * (1 - foreground.alpha)) / alpha,
            alpha: alpha
        )
    }
}

enum ReaderSpeechHighlightGeometry {
    /// Slices the painted tiles down to the span currently being spoken.
    ///
    /// `vertical` is supplied by the reader rather than guessed from each
    /// tile's aspect ratio. The old `height > width * 1.55` test is right for a
    /// full column but wrong for a one- or two-character run in a vertical
    /// book, which is wider than it is tall and was therefore cut across the
    /// glyphs instead of down them.
    static func rectangles(
        in sourceRectangles: [CGRect],
        highlight: ReaderSpeechHighlight?,
        vertical: Bool = false
    ) -> [CGRect] {
        guard let highlight, !sourceRectangles.isEmpty else { return [] }
        let lower = min(max(highlight.lowerBound, 0), 1)
        let upper = min(max(highlight.upperBound, lower), 1)
        guard upper > lower else { return [] }

        let count = Double(sourceRectangles.count)
        let lowerPosition = min(lower * count, max(count - .ulpOfOne, 0))
        let upperPosition = min(upper * count, count)
        let firstIndex = min(Int(floor(lowerPosition)), sourceRectangles.count - 1)
        let lastIndex = min(
            max(Int(ceil(upperPosition)) - 1, firstIndex),
            sourceRectangles.count - 1
        )

        return (firstIndex ... lastIndex).compactMap { index in
            let rectangle = sourceRectangles[index].standardized
            guard !rectangle.isEmpty else { return nil }
            let segmentStart = Double(index) / count
            let segmentEnd = Double(index + 1) / count
            let segmentLength = max(segmentEnd - segmentStart, .ulpOfOne)
            let localLower = min(max((lower - segmentStart) / segmentLength, 0), 1)
            let localUpper = min(max((upper - segmentStart) / segmentLength, localLower), 1)
            guard localUpper > localLower else { return nil }

            if vertical || rectangle.height > rectangle.width * 1.55 {
                return CGRect(
                    x: rectangle.minX,
                    y: rectangle.minY + rectangle.height * localLower,
                    width: rectangle.width,
                    height: rectangle.height * (localUpper - localLower)
                )
            }
            return CGRect(
                x: rectangle.minX + rectangle.width * localLower,
                y: rectangle.minY,
                width: rectangle.width * (localUpper - localLower),
                height: rectangle.height
            )
        }
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
struct EPUBSelectionGestureGate {
    static let defaultSuppressionDuration: TimeInterval = 1.5

    private(set) var suppressTapUntilUptime: TimeInterval?

    mutating func noteNativeSelection(
        at uptime: TimeInterval,
        suppressionDuration: TimeInterval = Self.defaultSuppressionDuration
    ) {
        guard uptime.isFinite, suppressionDuration.isFinite else { return }
        let deadline = uptime + max(0, suppressionDuration)
        suppressTapUntilUptime = max(suppressTapUntilUptime ?? deadline, deadline)
    }

    mutating func shouldSuppressTap(at uptime: TimeInterval) -> Bool {
        guard uptime.isFinite, let deadline = suppressTapUntilUptime else {
            return false
        }
        guard uptime <= deadline else {
            suppressTapUntilUptime = nil
            return false
        }
        return true
    }

    mutating func reset() {
        suppressTapUntilUptime = nil
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

    /// The writing mode the *reader* resolved, which decides the axis the
    /// merger groups lines along. It comes from the host rather than from the
    /// page because a publication can declare vertical writing while the reader
    /// forces horizontal, and because some WebKit builds report
    /// `-epub-writing-mode: vertical-rl` as `horizontal-tb`.
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
        palette.fill.setFill()
        palette.stroke.setStroke()
        context.setLineWidth(0.75)

        for selectionRect in displayedRects {
            let path = UIBezierPath(
                roundedRect: selectionRect.insetBy(dx: -0.5, dy: -0.5),
                cornerRadius: min(3, selectionRect.height * 0.16)
            )
            path.fill()
            path.stroke()
        }

        palette.spokenFill.setFill()
        for speechRect in ReaderSpeechHighlightGeometry.rectangles(
            in: displayedRects,
            highlight: speechHighlight,
            vertical: verticalText
        ) {
            UIBezierPath(
                roundedRect: speechRect.insetBy(dx: -0.5, dy: -0.5),
                cornerRadius: min(3, speechRect.height * 0.16)
            ).fill()
        }
        context.restoreGState()
    }

    private static func merged(_ rects: [CGRect], vertical: Bool) -> [CGRect] {
        // Selection geometry can contain a ruby annotation box that fully
        // covers its kanji's own text band. Drop contained rectangles first so
        // the translucent fill and stroke are never painted twice in place.
        let standardized = rects
            .map(\.standardized)
            .filter { !$0.isEmpty }
        // "Swallowed" has to be decided by a strict total order. The rule used
        // to keep a box unless some other box contained it and was wider *or*
        // taller *or* earlier in the list. Two boxes differing by less than the
        // half-point slack each contain the other, and that disjunction then
        // fires both ways — the larger loses on area, the smaller loses on
        // index, and both are dropped. Sub-pixel-different overlapping
        // fragments are exactly what ruby geometry produces, so a whole
        // highlighted line could vanish.
        //
        // Ordering by area with the earlier index winning ties is
        // antisymmetric, so the largest box can never be dropped and at least
        // one survivor is guaranteed.
        let deduplicated = standardized.enumerated()
            .filter { candidate in
                let candidateArea = candidate.element.width * candidate.element.height
                return !standardized.enumerated().contains { other in
                    guard other.offset != candidate.offset,
                          other.element.insetBy(dx: -0.5, dy: -0.5)
                              .contains(candidate.element)
                    else { return false }
                    let otherArea = other.element.width * other.element.height
                    return otherArea > candidateArea
                        || (otherArea == candidateArea && other.offset < candidate.offset)
                }
            }
            .map(\.element)

        return groupIntoLines(deduplicated, vertical: vertical)
    }

    /// One tile per contiguous run per line.
    ///
    /// This used to group by `midY`, which silently assumes lines stack
    /// downwards. In a `vertical-rl` book lines stack sideways and runs advance
    /// down the column, so two contiguous halves of the same column had wildly
    /// different `midY` values, were treated as different lines, and were left
    /// as two tiles with a seam between them.
    ///
    /// The line axis flips with the writing mode instead: horizontal text
    /// stacks lines down y and runs along x, vertical text stacks lines along x
    /// and runs down y. Same algorithm the shared Kotlin module uses, so the
    /// two platforms paint identical tiles.
    private static func groupIntoLines(
        _ rects: [CGRect],
        vertical: Bool
    ) -> [CGRect] {
        guard rects.count > 1 else { return rects }

        let crossStart: (CGRect) -> CGFloat = vertical ? { $0.minX } : { $0.minY }
        let crossEnd: (CGRect) -> CGFloat = vertical ? { $0.maxX } : { $0.maxY }
        let runStart: (CGRect) -> CGFloat = vertical ? { $0.minY } : { $0.minX }
        let runEnd: (CGRect) -> CGFloat = vertical ? { $0.maxY } : { $0.maxX }

        // Two fragments belong to one line once they share this much of the
        // shorter cross extent.
        func sharesLine(_ first: CGRect, _ second: CGRect) -> Bool {
            let overlap = min(crossEnd(first), crossEnd(second))
                - max(crossStart(first), crossStart(second))
            guard overlap > 0 else { return false }
            let shorter = min(
                crossEnd(first) - crossStart(first),
                crossEnd(second) - crossStart(second)
            )
            guard shorter > 0 else { return false }
            return overlap / shorter >= 0.55
        }

        var buckets: [[CGRect]] = []
        for rect in rects.sorted(by: { crossStart($0) < crossStart($1) }) {
            if let index = buckets.firstIndex(where: { bucket in
                bucket.contains { sharesLine($0, rect) }
            }) {
                buckets[index].append(rect)
            } else {
                buckets.append([rect])
            }
        }

        struct Line {
            var crossStart: CGFloat
            var crossEnd: CGFloat
            var runs: [(start: CGFloat, end: CGFloat)]
        }

        var lines: [Line] = buckets.map { bucket in
            var runs: [(start: CGFloat, end: CGFloat)] = []
            for run in bucket
                .map({ (start: runStart($0), end: runEnd($0)) })
                .sorted(by: { $0.start < $1.start })
            {
                // Runs closer than this along the line are one visual run.
                if let last = runs.last, run.start <= last.end + 1.5 {
                    runs[runs.count - 1].end = max(last.end, run.end)
                } else {
                    runs.append(run)
                }
            }
            return Line(
                crossStart: bucket.map(crossStart).min() ?? 0,
                crossEnd: bucket.map(crossEnd).max() ?? 0,
                runs: runs
            )
        }
        .sorted { $0.crossStart < $1.crossStart }

        // A ruby base reserves room for its annotation, so its line reaches into
        // the neighbouring one. Let them meet at the midpoint of the overlap:
        // both bands stay centred on their own text and no pixel is painted
        // twice.
        for index in 1 ..< max(lines.count, 1) where lines.count > 1 {
            let previous = lines[index - 1]
            let current = lines[index]
            guard current.crossStart < previous.crossEnd else { continue }
            let boundary = (current.crossStart + previous.crossEnd) / 2
            guard boundary > previous.crossStart, boundary < current.crossEnd
            else { continue }
            lines[index - 1].crossEnd = boundary
            lines[index].crossStart = boundary
        }

        return lines.flatMap { line in
            line.runs.map { run in
                vertical
                    ? CGRect(
                        x: line.crossStart,
                        y: run.start,
                        width: line.crossEnd - line.crossStart,
                        height: run.end - run.start
                    )
                    : CGRect(
                        x: run.start,
                        y: line.crossStart,
                        width: run.end - run.start,
                        height: line.crossEnd - line.crossStart
                    )
            }
        }
    }
}
