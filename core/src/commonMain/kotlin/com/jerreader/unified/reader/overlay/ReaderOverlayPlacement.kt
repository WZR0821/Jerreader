package com.jerreader.unified.reader.overlay

import com.jerreader.unified.reader.geometry.ReaderPoint
import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.geometry.ReaderSize
import kotlin.math.abs
import kotlin.math.floor

/** The region of the viewport the card was given. */
enum class ReaderOverlayEdge {
    ABOVE,
    BELOW,
    LEFT,
    RIGHT,
    TOP
}

/**
 * A complete answer for one frame: which side of the selection the card sits
 * on, where its centre goes, and how large it is allowed to be.
 */
data class ReaderOverlayLayout(
    val edge: ReaderOverlayEdge,
    val position: ReaderPoint,
    val cardWidth: Double,
    val maximumCardHeight: Double,
    val availableHeight: Double
) {
    /** The card's frame, once it has been measured at [measuredHeight]. */
    fun frame(measuredHeight: Double): ReaderRect = ReaderRect.centered(
        center = position,
        size = ReaderSize(cardWidth, measuredHeight.coerceAtMost(maximumCardHeight))
    )
}

/** The inputs a caller has to supply; everything else has a shared default. */
data class ReaderOverlayRequest(
    val selectionFrame: ReaderRect?,
    val viewportSize: ReaderSize,
    val cardSize: ReaderSize,
    /**
     * A second region to keep clear when solving sideways. In a vertical book
     * the tapped sentence is one column but the *whole visible translated
     * passage* can span several, and a card that only avoided the sentence
     * still covered the text the reader was following.
     */
    val horizontalAvoidanceFrame: ReaderRect? = null,
    val topInset: Double = 58.0,
    val bottomInset: Double = 42.0,
    val horizontalInset: Double = 16.0,
    val gap: Double = 18.0,
    val minimumCardHeight: Double = 64.0,
    val preferredMaximumCardHeight: Double = 196.0,
    val prefersTop: Boolean = false,
    val prefersHorizontalAvoidance: Boolean = false,
    val minimumHorizontalCardWidth: Double = 144.0
)

/**
 * Decides where the translation card goes so it never covers the sentence it is
 * translating.
 *
 * The region is chosen from the free space *around the selection*, not from the
 * card's current height. That ordering matters: the card grows when a result
 * replaces the loading spinner, and a solver keyed on the card's own height
 * makes it flip sides mid-request, which is what made the old Android card
 * visibly jump.
 *
 * This replaces two different implementations. iOS had a pure solver like this
 * one but only ran it once, before measurement. Android had no solver — the
 * region came from a chain of Compose modifiers — plus a post-measure collision
 * pass in `onGloballyPositioned` that nudged the card by an accumulating offset.
 * Both halves are here now: [solve] picks the region, [correct] takes the
 * measured frame and returns a *fresh* absolute shift rather than accumulating
 * one, so a card can no longer drift a little further away on every recomposition.
 */
object ReaderOverlayPlacement {

    fun solve(request: ReaderOverlayRequest): ReaderOverlayLayout {
        val viewportWidth = request.viewportSize.width.coerceAtLeast(1.0)
        val viewportHeight = request.viewportSize.height.coerceAtLeast(1.0)
        val safeTop = request.topInset.coerceIn(0.0, viewportHeight)
        val safeBottom = (viewportHeight - request.bottomInset.coerceAtLeast(0.0))
            .coerceAtLeast(safeTop)
        val safeHeight = (safeBottom - safeTop).coerceAtLeast(1.0)
        val minimumHeight = request.minimumCardHeight.coerceIn(1.0, safeHeight)
        val preferredHeight = request.preferredMaximumCardHeight
            .coerceAtLeast(minimumHeight)
            .coerceAtMost(safeHeight)

        val fallback = topFallback(
            request = request,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            safeTop = safeTop,
            safeBottom = safeBottom,
            safeHeight = safeHeight,
            preferredHeight = preferredHeight
        )

        val rawFrame = request.selectionFrame?.standardized()
        if (rawFrame == null || !rawFrame.isUsable) return fallback

        val viewportBounds = ReaderRect(0.0, 0.0, viewportWidth, viewportHeight)
        val visibleFrame = rawFrame.intersection(viewportBounds) ?: return fallback

        // "Top banner" is a preference, not permission to cover the sentence
        // being translated. Keep the banner when it is clear and fall through to
        // the ordinary above/below solver when it would collide.
        if (request.prefersTop) {
            val bannerFrame = fallback.frame(request.cardSize.height.coerceAtLeast(1.0))
            if (!bannerFrame.intersects(visibleFrame.inset(-request.gap, -request.gap))) {
                return fallback
            }
        }

        if (request.prefersHorizontalAvoidance) {
            solveSideways(
                request = request,
                visibleFrame = visibleFrame,
                viewportBounds = viewportBounds,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                safeTop = safeTop,
                safeBottom = safeBottom,
                safeHeight = safeHeight,
                preferredHeight = preferredHeight
            )?.let { return it }
        }

        return solveVertically(
            request = request,
            visibleFrame = visibleFrame,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            safeTop = safeTop,
            safeBottom = safeBottom,
            safeHeight = safeHeight,
            minimumHeight = minimumHeight,
            preferredHeight = preferredHeight
        )
    }

    private fun topFallback(
        request: ReaderOverlayRequest,
        viewportWidth: Double,
        viewportHeight: Double,
        safeTop: Double,
        safeBottom: Double,
        safeHeight: Double,
        preferredHeight: Double
    ): ReaderOverlayLayout {
        val cardHeight = request.cardSize.height.coerceIn(1.0, preferredHeight)
        val position = clampedPosition(
            position = ReaderPoint(viewportWidth / 2.0, safeTop + cardHeight / 2.0),
            viewportSize = ReaderSize(viewportWidth, viewportHeight),
            cardSize = ReaderSize(request.cardSize.width, cardHeight),
            topInset = safeTop,
            bottomInset = viewportHeight - safeBottom,
            horizontalInset = request.horizontalInset
        )
        return ReaderOverlayLayout(
            edge = ReaderOverlayEdge.TOP,
            position = position,
            cardWidth = request.cardSize.width,
            maximumCardHeight = preferredHeight,
            availableHeight = safeHeight
        )
    }

    /**
     * A vertical publication normally leaves usable space beside the selected
     * column while the selection itself runs almost the full page height, so the
     * card belongs to the side of it. Long horizontal results take this path too
     * when they would otherwise be squeezed above or below.
     */
    private fun solveSideways(
        request: ReaderOverlayRequest,
        visibleFrame: ReaderRect,
        viewportBounds: ReaderRect,
        viewportWidth: Double,
        viewportHeight: Double,
        safeTop: Double,
        safeBottom: Double,
        safeHeight: Double,
        preferredHeight: Double
    ): ReaderOverlayLayout? {
        val safeHorizontalInset = request.horizontalInset.coerceIn(0.0, viewportWidth / 2.0)
        val safeLeft = safeHorizontalInset
        val safeRight = viewportWidth - safeHorizontalInset
        val preferredWidth = request.cardSize.width
            .coerceAtLeast(1.0)
            .coerceAtMost((safeRight - safeLeft).coerceAtLeast(1.0))
        val requiredWidth = preferredWidth
            .coerceAtMost(request.minimumHorizontalCardWidth.coerceAtLeast(1.0))

        val avoidanceFrames = listOfNotNull(
            visibleFrame,
            request.horizontalAvoidanceFrame?.standardized()?.intersection(viewportBounds)
        ).filter { it.isUsable }

        for (avoidanceFrame in avoidanceFrames) {
            val roomLeft = (avoidanceFrame.left - request.gap - safeLeft).coerceAtLeast(0.0)
            val roomRight = (safeRight - request.gap - avoidanceFrame.right).coerceAtLeast(0.0)
            val leftFits = roomLeft >= requiredWidth
            val rightFits = roomRight >= requiredWidth
            if (!leftFits && !rightFits) continue

            val measuredWidth = preferredWidth.coerceAtMost(floor(maxOf(roomLeft, roomRight)))
            val edge = if (leftFits && (!rightFits || roomLeft >= roomRight)) {
                ReaderOverlayEdge.LEFT
            } else {
                ReaderOverlayEdge.RIGHT
            }
            val measuredHeight = request.cardSize.height.coerceIn(1.0, preferredHeight)
            val proposedX = if (edge == ReaderOverlayEdge.LEFT) {
                avoidanceFrame.left - request.gap - measuredWidth / 2.0
            } else {
                avoidanceFrame.right + request.gap + measuredWidth / 2.0
            }
            val position = clampedPosition(
                position = ReaderPoint(proposedX, avoidanceFrame.midY),
                viewportSize = ReaderSize(viewportWidth, viewportHeight),
                cardSize = ReaderSize(measuredWidth, measuredHeight),
                topInset = safeTop,
                bottomInset = viewportHeight - safeBottom,
                horizontalInset = request.horizontalInset
            )
            return ReaderOverlayLayout(
                edge = edge,
                position = position,
                cardWidth = measuredWidth,
                maximumCardHeight = preferredHeight,
                availableHeight = safeHeight
            )
        }
        return null
    }

    private fun solveVertically(
        request: ReaderOverlayRequest,
        visibleFrame: ReaderRect,
        viewportWidth: Double,
        viewportHeight: Double,
        safeTop: Double,
        safeBottom: Double,
        safeHeight: Double,
        minimumHeight: Double,
        preferredHeight: Double
    ): ReaderOverlayLayout {
        val roomAbove = (visibleFrame.top - request.gap - safeTop).coerceAtLeast(0.0)
        val roomBelow = (safeBottom - request.gap - visibleFrame.bottom).coerceAtLeast(0.0)
        val aboveFitsMinimum = roomAbove >= minimumHeight
        val belowFitsMinimum = roomBelow >= minimumHeight

        // When neither side fits a compact card the selection covers most of a
        // small screen. Take the larger remainder and stay compact rather than
        // expanding across the selected text.
        val edge = when {
            aboveFitsMinimum && belowFitsMinimum ->
                if (roomAbove >= roomBelow) ReaderOverlayEdge.ABOVE else ReaderOverlayEdge.BELOW
            aboveFitsMinimum -> ReaderOverlayEdge.ABOVE
            belowFitsMinimum -> ReaderOverlayEdge.BELOW
            else ->
                if (roomAbove >= roomBelow) ReaderOverlayEdge.ABOVE else ReaderOverlayEdge.BELOW
        }

        // Neither gap holds even a compact card, so there is no arrangement
        // that both shows the translation and leaves the sentence uncovered.
        // Staying compact then means a card capped at `MINIMUM_CARD_HEIGHT`
        // with its text cut off inside it — 「有时候，译文界面不展开，会这样很
        // 小」 — which is not a smaller version of the answer, it is no answer.
        // Cover part of the page instead: the card is draggable and can be
        // dismissed, and the reader can read what it says.
        val overlapsSelection = !aboveFitsMinimum && !belowFitsMinimum

        val roomForEdge = if (edge == ReaderOverlayEdge.ABOVE) roomAbove else roomBelow
        val availableHeight = if (overlapsSelection) safeHeight else roomForEdge
        val maximumCardHeight = availableHeight
            .coerceAtLeast(minimumHeight)
            .coerceAtMost(preferredHeight)
        val measuredHeight = request.cardSize.height.coerceIn(1.0, maximumCardHeight)
        val proposedY = when (edge) {
            // While overlapping, the card is pushed to the far edge of the safe
            // region rather than butted against the selection, so it covers as
            // little of the sentence as the page allows.
            ReaderOverlayEdge.ABOVE ->
                if (overlapsSelection) safeTop + measuredHeight / 2.0
                else visibleFrame.top - request.gap - measuredHeight / 2.0
            ReaderOverlayEdge.BELOW ->
                if (overlapsSelection) safeBottom - measuredHeight / 2.0
                else visibleFrame.bottom + request.gap + measuredHeight / 2.0
            ReaderOverlayEdge.LEFT, ReaderOverlayEdge.RIGHT -> visibleFrame.midY
            ReaderOverlayEdge.TOP -> safeTop + measuredHeight / 2.0
        }

        val position = clampedPosition(
            position = ReaderPoint(visibleFrame.midX, proposedY),
            viewportSize = ReaderSize(viewportWidth, viewportHeight),
            cardSize = ReaderSize(request.cardSize.width, measuredHeight),
            topInset = safeTop,
            bottomInset = viewportHeight - safeBottom,
            horizontalInset = request.horizontalInset
        )
        return ReaderOverlayLayout(
            edge = edge,
            position = position,
            cardWidth = request.cardSize.width,
            maximumCardHeight = maximumCardHeight,
            availableHeight = availableHeight
        )
    }

    /** Keeps a card centre inside the safe region, used for drags too. */
    fun clampedPosition(
        position: ReaderPoint,
        viewportSize: ReaderSize,
        cardSize: ReaderSize,
        topInset: Double,
        bottomInset: Double,
        horizontalInset: Double = 14.0
    ): ReaderPoint {
        val viewportWidth = viewportSize.width.coerceAtLeast(1.0)
        val viewportHeight = viewportSize.height.coerceAtLeast(1.0)
        val cardWidth = cardSize.width.coerceIn(0.0, viewportWidth)
        val cardHeight = cardSize.height.coerceIn(0.0, viewportHeight)
        val halfWidth = cardWidth / 2.0
        val halfHeight = cardHeight / 2.0
        val minimumX = minOf(viewportWidth / 2.0, horizontalInset + halfWidth)
        val maximumX = maxOf(minimumX, viewportWidth - horizontalInset - halfWidth)
        val minimumY = minOf(viewportHeight / 2.0, topInset + halfHeight)
        val maximumY = maxOf(minimumY, viewportHeight - bottomInset - halfHeight)
        return ReaderPoint(
            x = position.x.coerceIn(minimumX, maximumX),
            y = position.y.coerceIn(minimumY, maximumY)
        )
    }

    /**
     * Converts a rectangle reported in window coordinates into the overlay's own
     * space. Reader content can extend under the safe areas while the overlay
     * starts inside them, so the two origins must not be assumed to match.
     */
    fun localSelectionFrame(windowFrame: ReaderRect?, overlayFrame: ReaderRect): ReaderRect? {
        if (windowFrame == null || !windowFrame.isUsable) return null
        if (!overlayFrame.x.isFinite() || !overlayFrame.y.isFinite()) return null
        return windowFrame.standardized().offset(-overlayFrame.x, -overlayFrame.y)
    }

    // ---------------------------------------------------------------- correction

    /** How far a measured card has to move to clear the selection. */
    data class ReaderOverlayShift(val dx: Double, val dy: Double) {
        val magnitude: Double get() = abs(dx) + abs(dy)
        val isNegligible: Boolean get() = magnitude <= NEGLIGIBLE_SHIFT

        companion object {
            val None = ReaderOverlayShift(0.0, 0.0)
        }
    }

    /** Shifts smaller than this are layout noise, not a collision. */
    private const val NEGLIGIBLE_SHIFT = 0.5

    /**
     * Corrects a card that has already been measured and still overlaps.
     *
     * The solver runs before the card knows its real height, and text wrapping,
     * font scaling or a loading/result swap can all make the measured card
     * larger than the estimate it was placed from. This second pass takes the
     * *measured* frame and returns the shift from the card's solved position —
     * an absolute correction, not a delta to add to a previous one, so repeated
     * recompositions converge on the same answer instead of walking the card
     * across the page.
     *
     * Candidates are the four ways out of the selection. A candidate that fully
     * clears both the selection and the safe bounds wins on the shortest move;
     * if none is completely clear, the least-bad one is scored with the overlap
     * area dominating, because covering the sentence is worse than a card that
     * pokes slightly past the safe inset.
     */
    fun correct(
        measuredFrame: ReaderRect,
        selectionFrame: ReaderRect?,
        safeBounds: ReaderRect,
        gap: Double
    ): ReaderOverlayShift {
        val selection = selectionFrame?.standardized()
        if (selection == null || !selection.isUsable) return ReaderOverlayShift.None
        if (!measuredFrame.isUsable || !safeBounds.isUsable) return ReaderOverlayShift.None

        val expanded = selection.inset(-gap, -gap)

        fun shifted(shift: ReaderOverlayShift) = measuredFrame.offset(shift.dx, shift.dy)

        fun isClear(shift: ReaderOverlayShift): Boolean {
            val frame = shifted(shift)
            val withinSafeBounds = frame.left >= safeBounds.left - 1.0 &&
                frame.right <= safeBounds.right + 1.0 &&
                frame.top >= safeBounds.top - 1.0 &&
                frame.bottom <= safeBounds.bottom + 1.0
            return withinSafeBounds && !frame.intersects(expanded)
        }

        fun score(shift: ReaderOverlayShift): Double {
            val frame = shifted(shift)
            val overlap = frame.intersection(expanded)
            val overlapArea = if (overlap == null) 0.0 else overlap.width * overlap.height
            val overflow = (safeBounds.left - frame.left).coerceAtLeast(0.0) +
                (frame.right - safeBounds.right).coerceAtLeast(0.0) +
                (safeBounds.top - frame.top).coerceAtLeast(0.0) +
                (frame.bottom - safeBounds.bottom).coerceAtLeast(0.0)
            return overlapArea * 1000.0 + overflow * 100.0 + shift.magnitude
        }

        if (isClear(ReaderOverlayShift.None)) return ReaderOverlayShift.None

        val candidates = listOf(
            ReaderOverlayShift(0.0, expanded.bottom - measuredFrame.top),
            ReaderOverlayShift(0.0, expanded.top - measuredFrame.bottom),
            ReaderOverlayShift(expanded.left - measuredFrame.right, 0.0),
            ReaderOverlayShift(expanded.right - measuredFrame.left, 0.0)
        )
        val shift = candidates.filter(::isClear).minByOrNull { it.magnitude }
            ?: candidates.minByOrNull(::score)
            ?: ReaderOverlayShift.None
        return if (shift.isNegligible) ReaderOverlayShift.None else shift
    }
}
