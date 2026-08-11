package com.jerreader.unified.reader.overlay

import com.jerreader.unified.reader.geometry.ReaderRect

/**
 * The rules that turn "what is on screen" into the numbers [ReaderOverlayPlacement]
 * solves with: how far to stay off the selection, whether to try the sides at
 * all, and how large the card may get.
 *
 * These lived as scattered constants on both platforms — iOS had them as static
 * methods next to the solver, Android had them inline in a Compose function —
 * so the same book could get a 12pt gap on one platform and 26 on the other.
 */
object ReaderTranslationLayoutPolicy {

    /**
     * Whether the card should try to sit beside the selected column rather than
     * above or below it.
     *
     * Only reflowable publications rendered in their own writing mode qualify:
     * a fixed-layout page or a book forced horizontal has no spare column.
     * EPUB language metadata is optional and frequently absent, so a tall,
     * narrow live selection is accepted as evidence of a vertical column —
     * ordinary horizontal paragraphs are nowhere near narrow enough to pass it.
     */
    fun usesVerticalSideAvoidance(
        isReflowable: Boolean,
        preservesPublicationOrientation: Boolean,
        publicationIsVertical: Boolean,
        selectionFrame: ReaderRect?
    ): Boolean {
        if (!isReflowable || !preservesPublicationOrientation) return false
        if (publicationIsVertical) return true
        val frame = selectionFrame?.standardized() ?: return false
        if (!frame.isUsable) return false
        return frame.height >= 120.0 && frame.height >= frame.width * 1.6
    }

    /**
     * Clearance between the selection and the card. Sideways placement uses a
     * tighter gap because on a phone every point taken from the horizontal axis
     * comes straight out of the card's usable width.
     */
    fun selectionGap(isParagraph: Boolean, usesVerticalSideAvoidance: Boolean): Double = when {
        usesVerticalSideAvoidance -> 12.0
        isParagraph -> 26.0
        else -> 18.0
    }

    /** Tablets get a wider card; a sideways card starts narrower still. */
    fun preferredCardWidth(
        viewportWidth: Double,
        translatedCharacterCount: Int,
        isLoading: Boolean,
        isFailure: Boolean,
        usesVerticalSideAvoidance: Boolean
    ): Double {
        val regular = viewportWidth >= 700
        val availableWidth = (viewportWidth - 32.0).coerceAtLeast(1.0)
        val maximumWidth = minOf(availableWidth, if (regular) 440.0 else 376.0)
        val minimumWidth = minOf(maximumWidth, 260.0)

        val preferred = when {
            isLoading -> 304.0
            isFailure -> 340.0
            translatedCharacterCount <= 0 -> minimumWidth
            translatedCharacterCount <= 24 -> 300.0
            translatedCharacterCount <= 90 -> 334.0
            else -> if (regular) 420.0 else 376.0
        }
        val resolved = preferred.coerceIn(minimumWidth, maximumWidth)
        if (!usesVerticalSideAvoidance) return resolved

        // Still an ordinary floating window: it starts narrow so it can sit
        // immediately beside the original column, and the solver may shrink it
        // further on a phone rather than docking it to an edge.
        return minOf(resolved, if (regular) 320.0 else 220.0)
    }

    /**
     * A longer request earns a taller viewport. The card still hugs its measured
     * content and only reaches this cap when the text genuinely needs to scroll.
     */
    fun prefersExpandedMaximumHeight(
        sourceCharacterCount: Int,
        translatedCharacterCount: Int,
        isParagraph: Boolean
    ): Boolean = if (isParagraph) {
        translatedCharacterCount >= 40 || sourceCharacterCount >= 72
    } else {
        translatedCharacterCount >= 64 || sourceCharacterCount >= 96
    }

    fun preferredMaximumCardHeight(
        viewportSize: com.jerreader.unified.reader.geometry.ReaderSize,
        prefersExpanded: Boolean
    ): Double {
        val regular = viewportSize.width >= 700
        return if (prefersExpanded) {
            minOf(maxOf(viewportSize.height * 0.38, 260.0), if (regular) 360.0 else 300.0)
        } else {
            minOf(maxOf(viewportSize.height * 0.30, 166.0), if (regular) 286.0 else 246.0)
        }
    }

    fun horizontalInset(usesVerticalSideAvoidance: Boolean): Double =
        if (usesVerticalSideAvoidance) 12.0 else 16.0

    /** The smallest card the solver will place; below this it stops looking. */
    const val MINIMUM_CARD_HEIGHT = 108.0

    fun minimumHorizontalCardWidth(usesVerticalSideAvoidance: Boolean): Double =
        if (usesVerticalSideAvoidance) 144.0 else 220.0
}
