package com.jerreader.shared.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header lays itself out by arithmetic rather than letting the row
 * negotiate, so the arithmetic has to be right on its own. The invariant
 * throughout: whatever the header decides to show must sum to no more than the
 * card is wide, and the drag handle must never be squeezed below a real touch
 * target while a droppable title is still on screen.
 */
class TranslationCardHeaderMetricsTests {

    private val metrics = TranslationCardHeaderMetrics

    /** Phone portrait through tablet, at 0.5dp resolution. */
    private val cardWidths: List<Int> = (280..1200).toList()

    /** Two fixed buttons ("…" and close), optionally the favourite star. */
    private val buttonCounts = listOf(2, 3)

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
    fun showingTheTitleAlwaysLeavesARealHandle() {
        var checked = 0
        for (width in cardWidths) {
            for (count in buttonCounts) {
                if (!metrics.fitsTitle(width.dp, count)) continue
                val handle = metrics.flexibleWidth(width.dp, count) -
                    metrics.titleWidth - metrics.spacing
                assertTrue(
                    handle >= metrics.handleSlotMinimum,
                    "cardWidth=$width buttons=$count handle=$handle"
                )
                checked += 1
            }
        }
        assertTrue(checked > 1_000, "swept only $checked combinations")
    }

    @Test
    fun theRowNeverOverflowsTheCard() {
        for (width in cardWidths) {
            for (count in buttonCounts) {
                val flexible = metrics.flexibleWidth(width.dp, count)
                val content = if (metrics.fitsTitle(width.dp, count)) {
                    // Title, then whatever is left goes to the handle.
                    metrics.titleWidth + metrics.spacing +
                        (flexible - metrics.titleWidth - metrics.spacing)
                } else {
                    // No title: the handle takes the flexible space, which the
                    // row clamps at zero when there is none to give.
                    if (flexible > 0.dp) flexible else 0.dp
                }
                val total = metrics.horizontalPadding * 2 + content +
                    metrics.spacing + metrics.trailingWidth(count)
                val floor = metrics.horizontalPadding * 2 + metrics.spacing +
                    metrics.trailingWidth(count)
                assertTrue(
                    total <= maxOf(width.dp, floor) + 0.001.dp,
                    "cardWidth=$width buttons=$count total=$total"
                )
            }
        }
    }

    /**
     * The narrow case the report was about: a small dialog must give up the
     * title rather than draw the handle underneath the "…" button.
     */
    @Test
    fun aNarrowCardDropsTheTitleInsteadOfTheHandle() {
        assertFalse(metrics.fitsTitle(200.dp, 3), "a 200dp card cannot seat title and handle")
        assertFalse(metrics.fitsTitle(200.dp, 2))
    }

    /** A comfortable card keeps everything, star included. */
    @Test
    fun aFullWidthPhoneCardKeepsTitleAndStar() {
        assertTrue(metrics.fitsTitle(390.dp, 3), "a 390dp card should seat the whole header")
    }

    /**
     * Folding the star is only worth doing when it actually buys the title;
     * otherwise the header would lose a button for nothing.
     */
    @Test
    fun droppingTheStarOnlyEverHelps() {
        for (width in cardWidths) {
            if (metrics.fitsTitle(width.dp, 3)) {
                assertTrue(
                    metrics.fitsTitle(width.dp, 2),
                    "cardWidth=$width: fewer buttons must never fit less"
                )
            }
        }
    }

    @Test
    fun theHandleMinimumIsARealTouchTarget() {
        assertTrue(metrics.handleSlotMinimum >= 44.dp)
        assertTrue(metrics.titleWidth > 0.dp)
    }
}
