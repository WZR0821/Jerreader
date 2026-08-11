import JerreaderCore
import UIKit

/// Resolves the user's reading-mode choice without depending on Readium's
/// transient `settings.scroll` value while a spread is being rebuilt.
///
/// Readium intentionally forces vertical text into scroll mode, and also
/// changes `scroll` asynchronously after an orientation switch. Those are
/// implementation details: when the user selected 逐页, Jerreader either uses
/// native horizontal paging or its vertical-column snap. VoiceOver remains the
/// exception because Readium requires continuous scrolling for accessibility.
struct EPUBPagingPolicy: Equatable {
    let isPaginated: Bool
    let usesNativePaging: Bool
    let usesVerticalSnapping: Bool

    static func resolve(
        readingMode: ReaderReadingMode,
        rendersVerticalText: Bool,
        voiceOverRunning: Bool
    ) -> EPUBPagingPolicy {
        guard readingMode == .paginated, !voiceOverRunning else {
            return EPUBPagingPolicy(
                isPaginated: false,
                usesNativePaging: false,
                usesVerticalSnapping: false
            )
        }
        return EPUBPagingPolicy(
            isPaginated: true,
            usesNativePaging: !rendersVerticalText,
            usesVerticalSnapping: rendersVerticalText
        )
    }
}

/// What a vertical (`vertical-rl`) spread needs to know about the columns
/// WebKit laid out for it.
///
/// The column boxes are measured once per document and then answer every
/// question about the page without touching JavaScript again — which matters
/// because the gutters below are recomputed on every scroll frame.
final class ReaderVerticalColumnMetrics: NSObject {

    /// The measured column boxes, or nil while the spread has not been read
    /// yet. Both are held so a document that measures an advance but no usable
    /// boxes still turns pages by the estimate.
    let grid: ReaderVerticalColumnGrid?

    /// One column's width, for the estimate in `ReaderVerticalPageStep`.
    let columnAdvance: Double?

    /// The colour of the page behind the text, which is what the gutters are
    /// painted with. Taken from the document rather than from the theme, so a
    /// custom background or a publication stylesheet is followed exactly.
    let pageColor: UIColor?

    /// True while the measurement is in flight. The slot is claimed up front so
    /// a burst of layout passes cannot start the same work several times over.
    let isPending: Bool

    /// The width of the laid-out document when this was measured. WebKit
    /// reflows on rotation without any preference changing, and column boxes
    /// from the old width describe a page that no longer exists.
    let contentWidth: Double

    var isMeasured: Bool { !isPending && (grid?.isUsable == true || columnAdvance != nil) }

    /// Whether the page geometry — as opposed to only the page-turn estimate —
    /// is known well enough to cover slivers with.
    var hasColumnBoxes: Bool { grid?.isUsable == true }

    init(
        grid: ReaderVerticalColumnGrid?,
        columnAdvance: Double?,
        pageColor: UIColor?,
        contentWidth: Double,
        isPending: Bool = false
    ) {
        self.grid = grid
        self.columnAdvance = columnAdvance
        self.pageColor = pageColor
        self.contentWidth = contentWidth
        self.isPending = isPending
    }

    static var pending: ReaderVerticalColumnMetrics {
        ReaderVerticalColumnMetrics(
            grid: nil,
            columnAdvance: nil,
            pageColor: nil,
            contentWidth: 0,
            isPending: true
        )
    }

    /// Whether a document this wide is still the one that was measured. A
    /// pending measurement is never stale — it has not been taken yet.
    func describes(contentWidth width: Double) -> Bool {
        isPending || abs(width - contentWidth) <= 1
    }
}

/// Covers the part-columns clipped by the two edges of a vertical page.
///
/// A viewport is never a whole number of columns wide, so wherever the spread
/// sits there is a fraction of a column at one edge or both. Painting them in
/// the page colour is what turns "the outermost column is half on the next
/// page" into a margin — the same margin a printed page has anyway.
///
/// Deliberately not a clip or a mask on the web view: WebKit owns that layer
/// and Readium resets it, and a clipped web view also clips the native
/// selection handles. Two plain views over the top own nothing and break
/// nothing.
@MainActor
final class EPUBVerticalGutterOverlay: UIView {

    private let leadingCover = UIView()
    private let trailingCover = UIView()

    /// The widths currently painted, for the layout diagnostics the UI tests
    /// read.
    private(set) var coveredWidths: (leading: CGFloat, trailing: CGFloat) = (0, 0)

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        for cover in [leadingCover, trailingCover] {
            cover.isUserInteractionEnabled = false
            cover.isHidden = true
            addSubview(cover)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func hideGutters() {
        guard coveredWidths != (0, 0) || !leadingCover.isHidden || !trailingCover.isHidden
        else { return }
        coveredWidths = (0, 0)
        leadingCover.isHidden = true
        trailingCover.isHidden = true
    }

    /// - Parameter page: the spread's visible rect, in this view's coordinates.
    func cover(page: CGRect, leading: CGFloat, trailing: CGFloat, color: UIColor) {
        guard page.width > 1, page.height > 0 else {
            hideGutters()
            return
        }
        // Half a point of a line box carries no ink, so covering it would only
        // draw a hairline of paper over paper.
        let head = leading > 0.5 ? min(leading, page.width) : 0
        let tail = trailing > 0.5 ? min(trailing, page.width - head) : 0
        coveredWidths = (head, tail)

        leadingCover.isHidden = head <= 0
        leadingCover.backgroundColor = color
        leadingCover.frame = CGRect(x: page.minX, y: page.minY, width: head, height: page.height)

        trailingCover.isHidden = tail <= 0
        trailingCover.backgroundColor = color
        trailingCover.frame = CGRect(
            x: page.maxX - tail,
            y: page.minY,
            width: tail,
            height: page.height
        )
    }
}

/// Snaps a dragged vertical spread to a whole page.
///
/// `UIScrollView.isPagingEnabled` cannot do this job: it snaps to multiples of
/// the viewport measured from the content origin, and a page of columns is
/// deliberately *not* a viewport — it holds one column back so no column is
/// lost at the boundary. Paging the scroll view therefore skipped the column
/// straddling every viewport boundary, which is a column of text the reader
/// never got to read.
///
/// Readium already owns the scroll view's delegate, so this stands in front of
/// it and passes on the three methods `EPUBSpreadView` implements —
/// `scrollViewDidScroll`, `scrollViewWillBeginDragging` and
/// `scrollViewDidEndScrollingAnimation`. It answers `scrollViewWillEndDragging`
/// itself, which is the one Readium leaves free.
@MainActor
final class EPUBVerticalPagingScrollDelegate: NSObject, UIScrollViewDelegate {

    /// The delegate this one displaced — Readium's spread view.
    weak var forwarding: (any UIScrollViewDelegate)?

    /// Answers where the page containing `from` ends when dragged `movesLeft`,
    /// or nil to leave the drag alone.
    var pageTarget: ((_ scrollView: UIScrollView, _ from: CGFloat, _ movesLeft: Bool) -> CGFloat?)?

    /// The reader has taken hold of the page: whatever it was animating
    /// towards is no longer where it is going.
    var onDragBegin: (() -> Void)?

    private var dragOrigin: CGFloat?

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        forwarding?.scrollViewDidScroll?(scrollView)
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        forwarding?.scrollViewDidEndScrollingAnimation?(scrollView)
    }

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        dragOrigin = scrollView.contentOffset.x
        onDragBegin?()
        forwarding?.scrollViewWillBeginDragging?(scrollView)
    }

    func scrollViewWillEndDragging(
        _ scrollView: UIScrollView,
        withVelocity velocity: CGPoint,
        targetContentOffset: UnsafeMutablePointer<CGPoint>
    ) {
        // Preserve the system projection before the forwarded delegate has a
        // chance to alter it. Forward first for Readium's side effects, then
        // write our page boundary last so it cannot be replaced by a free-
        // scrolling target.
        let projected = targetContentOffset.pointee.x
        forwarding?.scrollViewWillEndDragging?(
            scrollView,
            withVelocity: velocity,
            targetContentOffset: targetContentOffset
        )
        guard let pageTarget, let origin = dragOrigin else { return }
        dragOrigin = nil

        // `targetContentOffset` is where the flick would coast to, so it already
        // carries the velocity: a short fast swipe and a long slow drag both
        // land past the threshold, which is how a page turn should feel.
        let travelled = projected - origin
        let threshold = max(scrollView.bounds.width * 0.18, 24)
        guard abs(travelled) >= threshold else {
            // Not far enough to be a page turn: put the page back.
            targetContentOffset.pointee.x = origin
            return
        }
        guard let target = pageTarget(scrollView, origin, travelled < 0) else {
            // Nothing to turn to — the end of the spread. Leave the drag where
            // it started rather than resting between two pages.
            targetContentOffset.pointee.x = origin
            return
        }
        targetContentOffset.pointee.x = target
    }
}

/// Reads a CSS colour of the kind `getComputedStyle` returns.
///
/// Only the `rgb()`/`rgba()` forms occur there — a computed background colour
/// is always resolved — and a fully transparent one means the element paints
/// nothing, which is not a colour to copy.
func readerCSSColor(_ value: String?) -> UIColor? {
    guard let value else { return nil }
    let trimmed = value.trimmingCharacters(in: .whitespaces).lowercased()
    guard trimmed.hasPrefix("rgb") ,
          let open = trimmed.firstIndex(of: "("),
          let close = trimmed.lastIndex(of: ")")
    else { return nil }
    let parts = trimmed[trimmed.index(after: open) ..< close]
        .split(whereSeparator: { $0 == "," || $0 == " " || $0 == "/" })
        .compactMap { Double($0) }
    guard parts.count >= 3 else { return nil }
    let alpha = parts.count >= 4 ? parts[3] : 1
    guard alpha > 0.01 else { return nil }
    return UIColor(
        red: CGFloat(parts[0] / 255),
        green: CGFloat(parts[1] / 255),
        blue: CGFloat(parts[2] / 255),
        alpha: CGFloat(alpha)
    )
}
