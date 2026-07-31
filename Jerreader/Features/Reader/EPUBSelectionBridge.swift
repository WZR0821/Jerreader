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
        let fillAlpha: CGFloat = darkBackground ? 0.42 : 0.30
        let readablePole: UIColor = darkBackground ? .white : .black

        // Judge the color the user actually sees after the translucent fill
        // is composited over the page. Checking the opaque accent alone can
        // accept a custom color that becomes nearly invisible at 30% alpha.
        for _ in 0 ..< 8 {
            let visibleFill = accent.withAlphaComponent(fillAlpha)
                .readerComposited(over: background)
            if contrastRatio(visibleFill, background) >= 1.28 { break }
            accent = accent.readerMixed(with: readablePole, amount: 0.18)
        }

        let stroke = accent.readerMixed(
            with: darkBackground ? .white : .black,
            amount: darkBackground ? 0.26 : 0.24
        ).withAlphaComponent(darkBackground ? 0.82 : 0.64)
        return ReaderSelectionVisualPalette(
            fill: accent.withAlphaComponent(fillAlpha),
            stroke: stroke,
            spokenFill: accent.withAlphaComponent(darkBackground ? 0.70 : 0.58)
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
    static func rectangles(
        in sourceRectangles: [CGRect],
        highlight: ReaderSpeechHighlight?
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

            if rectangle.height > rectangle.width * 1.55 {
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
    private var speechHighlight: ReaderSpeechHighlight?
    private var palette = ReaderSelectionVisualPalette.light

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
        displayedRects = Self.merged(rects)
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
            highlight: speechHighlight
        ) {
            UIBezierPath(
                roundedRect: speechRect.insetBy(dx: -0.5, dy: -0.5),
                cornerRadius: min(3, speechRect.height * 0.16)
            ).fill()
        }
        context.restoreGState()
    }

    private static func merged(_ rects: [CGRect]) -> [CGRect] {
        // Selection geometry can contain a ruby annotation box that fully
        // covers its kanji's own text band. Drop contained rectangles first so
        // the translucent fill and stroke are never painted twice in place.
        let standardized = rects
            .map(\.standardized)
            .filter { !$0.isEmpty }
        let deduplicated = standardized.enumerated()
            .filter { candidate in
                !standardized.enumerated().contains { other in
                    other.offset != candidate.offset
                        && other.element.insetBy(dx: -0.5, dy: -0.5)
                            .contains(candidate.element)
                        && (other.element.width > candidate.element.width
                            || other.element.height > candidate.element.height
                            || other.offset < candidate.offset)
                }
            }
            .map(\.element)

        let sorted = deduplicated
            .sorted {
                if abs($0.midY - $1.midY) < min($0.height, $1.height) * 0.45 {
                    return $0.minX < $1.minX
                }
                return $0.minY < $1.minY
            }

        return sorted.reduce(into: []) { result, candidate in
            guard let last = result.last else {
                result.append(candidate)
                return
            }
            let sameLine = abs(last.midY - candidate.midY)
                <= min(last.height, candidate.height) * 0.45
            let smallGap = candidate.minX - last.maxX <= 2
            if sameLine && smallGap {
                result[result.count - 1] = last.union(candidate)
            } else {
                result.append(candidate)
            }
        }
    }
}
