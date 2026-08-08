package com.jerreader.shared.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the translate card's header divides its width.
 *
 * The header is a row of fixed-size pieces around one flexible drag handle, and
 * the flexible piece is the one that pays for everything else. Left to itself
 * that goes wrong in both directions: Compose shrinks a weighted child to
 * nothing, so the handle silently becomes undraggable, while the unweighted
 * title keeps its size and pushes the buttons past the card's edge. On a narrow
 * card — a phone in split view, or the card docked beside a vertical page — the
 * result is a header with no handle and a "…" button hanging off the side.
 *
 * So the header decides by arithmetic instead: work out what the flexible space
 * would be, and if it cannot seat a real drag target, give something up. The
 * title goes first, because it says "译文" on a card that is obviously the
 * translation; the buttons are actions and cannot be guessed from context.
 */
object TranslationCardHeaderMetrics {
    /** Padding on each side of the header row. */
    val horizontalPadding: Dp = 10.dp

    /** One circular header action button. */
    val buttonWidth: Dp = 32.dp

    /** Gap between adjacent action buttons. */
    val buttonSpacing: Dp = 0.dp

    /** The 字 badge that opens the title. */
    val titleIconWidth: Dp = 24.dp

    /** Gap between the badge and the 译文 label. */
    val titleGap: Dp = 7.dp

    /** Enough for "译文" — below this the label is noise rather than a title. */
    val titleTextMinimum: Dp = 30.dp

    /** Gap between the flexible handle slot and the buttons beside it. */
    val spacing: Dp = 7.dp

    /**
     * The narrowest the handle slot may be and still be worth dragging. Below
     * this the header drops the title rather than shipping a handle nobody can
     * hit.
     */
    val handleSlotMinimum: Dp = 44.dp

    /** Everything the title occupies when it is shown. */
    val titleWidth: Dp = titleIconWidth + titleGap + titleTextMinimum

    /** Total width of [buttonCount] action buttons and the gaps between them. */
    fun trailingWidth(buttonCount: Int): Dp {
        val count = buttonCount.coerceAtLeast(0)
        return buttonWidth * count + buttonSpacing * (count - 1).coerceAtLeast(0)
    }

    /** What is left for the title and the handle once the buttons are placed. */
    fun flexibleWidth(cardWidth: Dp, buttonCount: Int): Dp =
        cardWidth - horizontalPadding * 2 - trailingWidth(buttonCount) - spacing

    /**
     * Whether the title fits beside a handle that is still worth dragging.
     *
     * This is the whole decision: everything else follows from it.
     */
    fun fitsTitle(cardWidth: Dp, buttonCount: Int): Boolean =
        flexibleWidth(cardWidth, buttonCount) >= titleWidth + spacing + handleSlotMinimum
}
