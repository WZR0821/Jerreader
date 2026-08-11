package com.jerreader.unified.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header lays itself out by arithmetic rather than letting the row
 * negotiate, so the arithmetic has to be right on its own. The invariant
 * throughout: whatever the action row decides to show must sum to no more than
 * the card is wide.
 *
 * The grabber is deliberately absent from all of it. It has a full-width row of
 * its own now, so it is centred in the card by construction and cannot be
 * squeezed by anything on the action row.
 */
class TranslationCardHeaderMetricsTests {

    private val metrics = TranslationCardHeaderMetrics

    /** Phone portrait through tablet. */
    private val cardWidths: List<Int> = (140..1200).toList()

    /** "…" and close, plus a couple of spares in case the row ever grows. */
    private val buttonCounts = listOf(1, 2, 3, 4)

    @Test
    fun trailingWidthCountsGapsNotEdges() {
        assertEquals(0.dp, metrics.trailingWidth(0))
        assertEquals(metrics.buttonWidth, metrics.trailingWidth(1))
        assertEquals(
            metrics.buttonWidth * 3 + metrics.buttonSpacing * 2,
            metrics.trailingWidth(3)
        )
    }

    @Test
    fun trailingWidthTreatsNegativeCountsAsEmpty() {
        assertEquals(0.dp, metrics.trailingWidth(-2))
    }

    @Test
    fun theActionRowNeverOverflowsWhenItShowsTheTitle() {
        var checked = 0
        for (width in cardWidths) {
            for (count in buttonCounts) {
                if (!metrics.fitsTitle(width.dp, count)) continue
                val total = metrics.horizontalPadding * 2 +
                    metrics.titleWidth +
                    metrics.spacing +
                    metrics.trailingWidth(count)
                assertTrue(
                    total <= width.dp + 0.001.dp,
                    "cardWidth=$width buttons=$count total=$total"
                )
                checked += 1
            }
        }
        assertTrue(checked > 1_000, "swept only $checked combinations")
    }

    /**
     * Dropping the title is only ever the second move; the first is dropping an
     * optional button. So whenever the buttons alone already overflow, the title
     * must already be gone.
     */
    @Test
    fun theTitleIsGoneBeforeTheButtonsOverflow() {
        for (width in cardWidths) {
            for (count in buttonCounts) {
                if (metrics.fitsTrailing(width.dp, count)) continue
                assertFalse(
                    metrics.fitsTitle(width.dp, count),
                    "cardWidth=$width buttons=$count: title kept on an overflowing row"
                )
            }
        }
    }

    /** Fewer buttons must never fit less. */
    @Test
    fun foldingAButtonOnlyEverHelps() {
        for (width in cardWidths) {
            for (count in buttonCounts) {
                if (metrics.fitsTitle(width.dp, count)) {
                    assertTrue(
                        metrics.fitsTitle(width.dp, count - 1),
                        "cardWidth=$width buttons=$count"
                    )
                }
                if (metrics.fitsTrailing(width.dp, count)) {
                    assertTrue(
                        metrics.fitsTrailing(width.dp, count - 1),
                        "cardWidth=$width buttons=$count"
                    )
                }
            }
        }
    }

    /**
     * The narrow case the report was about: a small dialog gives up the title
     * rather than push the "…" off its edge, but both buttons stay.
     */
    @Test
    fun aNarrowCardDropsTheTitleAndKeepsBothButtons() {
        assertFalse(metrics.fitsTitle(140.dp, 2), "a 140dp card cannot seat the title")
        assertTrue(metrics.fitsTrailing(140.dp, 2))
    }

    /** A comfortable card keeps everything. */
    @Test
    fun aFullWidthPhoneCardKeepsTheTitle() {
        assertTrue(metrics.fitsTitle(280.dp, 2), "a 280dp card should seat the whole header")
        assertTrue(metrics.fitsTitle(390.dp, 3))
    }

    @Test
    fun theHeaderReservesBothOfItsRows() {
        assertTrue(metrics.actionRowHeight >= 44.dp)
        assertTrue(metrics.handleRowHeight > 0.dp)
        assertEquals(metrics.handleRowHeight + metrics.actionRowHeight, metrics.height)
        assertTrue(metrics.titleWidth > 0.dp)
    }
}
