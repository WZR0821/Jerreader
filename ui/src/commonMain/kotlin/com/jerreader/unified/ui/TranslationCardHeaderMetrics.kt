package com.jerreader.unified.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How the translate card's header divides its width.
 *
 * The action row is a row of fixed-size pieces, and Compose will happily let an
 * unweighted title push the buttons past the card's edge rather than report that
 * the row did not fit. On a narrow card — a phone in split view, or the card
 * docked beside a vertical page — that leaves a "…" button hanging off the side.
 *
 * So the header decides by arithmetic instead: work out what is left once the
 * buttons are placed, and if the title does not fit, give it up. The title goes
 * first, because it says "译文" on a card that is obviously the translation; the
 * buttons are actions and cannot be guessed from context.
 *
 * The drag handle is deliberately absent from all of this. It has a full-width
 * row of its own above the actions, so it is centred in the card by construction
 * and there is nothing beside it to squeeze it — it used to be the flexible
 * piece here, which meant Compose could shrink it to nothing and leave a card
 * that could not be moved at all.
 */
object TranslationCardHeaderMetrics {
    /** Padding on each side of the action row. */
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

    /** Gap between the title and the buttons beside it. */
    val spacing: Dp = 7.dp

    /** The grabber's own row: full width, with the capsule centred in it. */
    val handleRowHeight: Dp = 22.dp

    /** The visible capsule. The hit target is the whole row around it. */
    val handleWidth: Dp = 36.dp

    /** The row carrying the title and the action buttons. */
    val actionRowHeight: Dp = 44.dp

    /** Both rows together. */
    val height: Dp = handleRowHeight + actionRowHeight

    /** Everything the title occupies when it is shown. */
    val titleWidth: Dp = titleIconWidth + titleGap + titleTextMinimum

    /** Total width of [buttonCount] action buttons and the gaps between them. */
    fun trailingWidth(buttonCount: Int): Dp {
        val count = buttonCount.coerceAtLeast(0)
        return buttonWidth * count + buttonSpacing * (count - 1).coerceAtLeast(0)
    }

    /** What is left for the title once the buttons are placed. */
    fun flexibleWidth(cardWidth: Dp, buttonCount: Int): Dp =
        cardWidth - horizontalPadding * 2 - trailingWidth(buttonCount) - spacing

    /**
     * Whether the buttons alone clear the card. Below this the row would draw
     * over itself, so an optional button has to go.
     */
    fun fitsTrailing(cardWidth: Dp, buttonCount: Int): Boolean =
        horizontalPadding * 2 + trailingWidth(buttonCount) <= cardWidth

    /**
     * Whether the title fits alongside those buttons.
     *
     * This is the whole decision: everything else follows from it.
     */
    fun fitsTitle(cardWidth: Dp, buttonCount: Int): Boolean =
        flexibleWidth(cardWidth, buttonCount) >= titleWidth
}
