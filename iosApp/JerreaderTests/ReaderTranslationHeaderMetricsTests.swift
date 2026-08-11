import XCTest
@testable import JerreaderUnified

/// The translate card's header lays itself out by arithmetic rather than by
/// letting SwiftUI negotiate: an `HStack` of `.fixedSize` children does not
/// report that it overflowed, it just draws its children on top of each other,
/// which is exactly how the drag handle ended up under the "…" button.
///
/// So the arithmetic has to be right on its own, and these tests are what say
/// so. The invariant throughout: the pieces the header decides to show must sum
/// to no more than the card is wide.
///
/// The grabber is deliberately absent from all of it. It has a full-width row
/// of its own now, so it is centred in the card by construction and cannot be
/// squeezed by, or laid over, anything on the action row.
final class ReaderTranslationHeaderMetricsTests: XCTestCase {

    private typealias Metrics = ReaderTranslationHeaderMetrics

    /// Every card width the reader can produce, at 0.5pt resolution: the card
    /// tracks the reading pane, which follows rotation, split view and the
    /// user's own drag. 144pt is the floor for a vertical book's side-avoiding
    /// card — see `ReaderTranslationLayoutPolicy`.
    private let cardWidths: [CGFloat] = stride(from: CGFloat(140), through: CGFloat(460), by: 0.5).map { $0 }

    /// 0 through 4 trailing buttons — close, actions, speech, and one spare in
    /// case the row ever grows another.
    private let buttonCounts = Array(0...4)

    func testTrailingWidthCountsGapsNotEdges() {
        XCTAssertEqual(Metrics.trailingWidth(buttonCount: 0), 0)
        XCTAssertEqual(Metrics.trailingWidth(buttonCount: 1), Metrics.buttonWidth)
        XCTAssertEqual(
            Metrics.trailingWidth(buttonCount: 3),
            Metrics.buttonWidth * 3 + Metrics.buttonSpacing * 2,
            accuracy: 0.001
        )
    }

    func testTrailingWidthTreatsNegativeCountsAsEmpty() {
        XCTAssertEqual(Metrics.trailingWidth(buttonCount: -2), 0)
    }

    /// The row is padding + title + a gap + the buttons. When the header says
    /// the title fits, all of that has to clear the card.
    func testTheActionRowNeverOverflowsWhenItShowsTheTitle() {
        var checked = 0
        for width in cardWidths {
            for count in buttonCounts where Metrics.fitsTitle(cardWidth: width, buttonCount: count) {
                let total = Metrics.horizontalPadding * 2
                    + Metrics.titleWidth
                    + Metrics.spacing
                    + Metrics.trailingWidth(buttonCount: count)
                XCTAssertLessThanOrEqual(
                    total,
                    width + 0.001,
                    "cardWidth=\(width) buttons=\(count) total=\(total)"
                )
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 1_000, "swept only \(checked) combinations")
    }

    /// Dropping the title is only ever the header's second move; the first is
    /// dropping the one optional button. So whenever the buttons alone already
    /// overflow, the title must already be gone.
    func testTheTitleIsGoneBeforeTheButtonsOverflow() {
        for width in cardWidths {
            for count in buttonCounts where !Metrics.fitsTrailing(cardWidth: width, buttonCount: count) {
                XCTAssertFalse(
                    Metrics.fitsTitle(cardWidth: width, buttonCount: count),
                    "cardWidth=\(width) buttons=\(count): title kept on an overflowing row"
                )
            }
        }
    }

    /// Fewer buttons must never fit less — otherwise folding one away could
    /// cost the header its title.
    func testFoldingAButtonOnlyEverHelps() {
        for width in cardWidths {
            for count in buttonCounts.dropFirst() {
                if Metrics.fitsTitle(cardWidth: width, buttonCount: count) {
                    XCTAssertTrue(
                        Metrics.fitsTitle(cardWidth: width, buttonCount: count - 1),
                        "cardWidth=\(width) buttons=\(count)"
                    )
                }
                if Metrics.fitsTrailing(cardWidth: width, buttonCount: count) {
                    XCTAssertTrue(
                        Metrics.fitsTrailing(cardWidth: width, buttonCount: count - 1),
                        "cardWidth=\(width) buttons=\(count)"
                    )
                }
            }
        }
    }

    /// The narrowest card the layout policy can hand the overlay. It gives up
    /// the title, but the two buttons that carry every action still fit.
    func testTheNarrowestVerticalCardStillSeatsBothButtons() {
        let narrowest: CGFloat = 144
        XCTAssertTrue(Metrics.fitsTrailing(cardWidth: narrowest, buttonCount: 2))
        XCTAssertFalse(Metrics.fitsTitle(cardWidth: narrowest, buttonCount: 2))
    }

    /// A phone-width card keeps everything the header has to show.
    func testAFullWidthPhoneCardKeepsTheTitle() {
        XCTAssertTrue(Metrics.fitsTitle(cardWidth: 300, buttonCount: 2))
        XCTAssertTrue(Metrics.fitsTitle(cardWidth: 390, buttonCount: 3))
    }

    /// The action row is a real touch target and the handle row is a visible
    /// one, and the card reserves both.
    func testTheHeaderReservesBothOfItsRows() {
        XCTAssertGreaterThanOrEqual(Metrics.actionRowHeight, 44)
        XCTAssertGreaterThan(Metrics.handleRowHeight, Metrics.handleWidth / 4)
        XCTAssertEqual(
            Metrics.height,
            Metrics.handleRowHeight + Metrics.actionRowHeight,
            accuracy: 0.001
        )
        XCTAssertGreaterThan(Metrics.titleWidth, 0)
    }
}
