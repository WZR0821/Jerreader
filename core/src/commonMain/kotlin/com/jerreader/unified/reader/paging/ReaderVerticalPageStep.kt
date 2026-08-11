package com.jerreader.unified.reader.paging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far one page-turn moves a vertical (`vertical-rl`) spread when the
 * columns themselves have not been measured.
 *
 * Vertical text is never paginated by ReadiumCSS, so the reader turns pages by
 * scrolling the spread horizontally. Stepping by exactly one viewport looks
 * right until you notice where the boundary lands: the viewport is almost never
 * a whole number of columns wide, so the column straddling the edge is half on
 * this page and half on the next, and is readable on neither.
 *
 * Holding one column back fixes both halves of that. The column clipped at the
 * leading edge of this page arrives complete on the next one, and the reader
 * gets a column of overlap to carry their eye across the turn — the same thing
 * a printed book gets for free by never splitting a column at all.
 *
 * This is the estimate, and it assumes every column is the same width.
 * [ReaderVerticalColumnGrid] is the exact answer, from the column boxes WebKit
 * actually laid out; this remains the fallback for the moment before the
 * measurement lands, and for a spread that measures nothing at all.
 */
object ReaderVerticalPageStep {

    /**
     * Never give up more than half the viewport to overlap. A column that wide
     * means the measurement is wrong (an empty spread, a single huge heading),
     * and a step of one column at a time would take dozens of taps to cross a
     * page.
     */
    private const val MAXIMUM_OVERLAP_FRACTION = 0.5

    /**
     * @param viewportWidth the spread's visible width.
     * @param columnAdvance one column's width, or null while it is unmeasured.
     * @return how far to scroll for one page turn, always positive.
     */
    fun advance(viewportWidth: Double, columnAdvance: Double?): Double {
        if (viewportWidth <= 0.0) return 0.0
        // Unmeasured, degenerate, or implausibly wide: fall back to a full
        // viewport, which is the behaviour this replaced. A slightly large step
        // is a worse read; a step of zero is a reader that cannot turn a page.
        if (!isUsable(columnAdvance, viewportWidth)) {
            return viewportWidth
        }
        return viewportWidth - columnAdvance!!
    }

    /**
     * Whether a step of this size guarantees no column is lost at the boundary.
     *
     * True exactly when the overlap is at least one column wide, which is the
     * property the whole rule exists to provide.
     */
    fun keepsEveryColumnWhole(
        viewportWidth: Double,
        columnAdvance: Double,
        step: Double
    ): Boolean {
        if (viewportWidth <= 0.0 || columnAdvance <= 0.0) return false
        return viewportWidth - step >= columnAdvance - 1e-9
    }

    internal fun isUsable(columnAdvance: Double?, viewportWidth: Double): Boolean {
        if (columnAdvance == null || !columnAdvance.isFinite()) return false
        if (columnAdvance <= 1.0) return false
        return columnAdvance < viewportWidth * MAXIMUM_OVERLAP_FRACTION
    }
}

/**
 * The columns of a vertical spread, as WebKit laid them out, and the two things
 * the reader needs from them: where a page turn lands, and which slivers at the
 * edges of the page are not a whole column and must be covered.
 *
 * Holding one column back at each turn ([ReaderVerticalPageStep.advance]) means
 * the split column comes back whole on the next page, but it does nothing about
 * *this* page still showing its sliver — which is what the reader sees, and
 * reads as broken. And it assumes a uniform grid, which a real page is not:
 * paragraph spacing is a gap along the block axis in `vertical-rl`, and a
 * heading's line height differs from the body's, so column edges drift out of
 * any fixed rhythm within a single screen. Measuring the boxes removes both
 * assumptions:
 *
 *  1. A turn moves to a boundary of a real column, so the last whole column of
 *     this page is the first whole column of the next — complete in both.
 *  2. [leadingGutterWidth] and [trailingGutterWidth] report the part-columns
 *     clipped by the two edges of the page, which the reader covers with the
 *     page colour, where they read as the margins a printed page has anyway.
 *
 * Alignment is deliberately never forced: pinning the page to a column boundary
 * would eat the document's own block-start padding and push the first column
 * against the edge of the screen. Wherever the spread happens to sit — after a
 * bookmark, or Readium's own progression scroll — the gutters describe that
 * position rather than change it.
 *
 * Coordinates are the scroll view's: x grows rightwards, the content spans
 * `[0, contentWidth]`, and the page is `[offset, offset + viewportWidth]`.
 * `vertical-rl` reads from the right end of that strip leftwards.
 *
 * @param columnEdges the column boxes as flattened `start, end` pairs in
 *   content coordinates. Malformed and out-of-order pairs are dropped rather
 *   than trusted; a grid built from nothing usable simply reports [isUsable]
 *   false and the caller falls back to [ReaderVerticalPageStep].
 */
class ReaderVerticalColumnGrid(columnEdges: List<Double>) {

    private val starts: DoubleArray
    private val ends: DoubleArray

    init {
        val boxes = ArrayList<Pair<Double, Double>>(columnEdges.size / 2)
        var index = 0
        while (index + 1 < columnEdges.size) {
            val start = columnEdges[index]
            val end = columnEdges[index + 1]
            index += 2
            if (!start.isFinite() || !end.isFinite()) continue
            if (end - start <= MINIMUM_COLUMN_WIDTH) continue
            boxes.add(start to end)
        }
        boxes.sortBy { it.first }
        starts = DoubleArray(boxes.size) { boxes[it].first }
        ends = DoubleArray(boxes.size) { boxes[it].second }
    }

    val columnCount: Int get() = starts.size

    /** Two columns is the least that can show one and hold one back. */
    val isUsable: Boolean get() = starts.size >= 2

    /**
     * The part-column clipped by the leading (left) edge of the page.
     *
     * Zero when the edge falls on a column boundary or in the gap between two
     * paragraphs, where there is nothing to hide.
     */
    fun leadingGutterWidth(offset: Double, viewportWidth: Double): Double {
        if (!isUsable || viewportWidth <= 0.0 || !offset.isFinite()) return 0.0
        val index = columnStraddling(offset)
        if (index < 0) return 0.0
        return capped(ends[index] - offset, viewportWidth)
    }

    /**
     * The part-column clipped by the trailing (right) edge of the page — the
     * remainder of the column the previous page ended on.
     */
    fun trailingGutterWidth(offset: Double, viewportWidth: Double): Double {
        if (!isUsable || viewportWidth <= 0.0 || !offset.isFinite()) return 0.0
        val edge = offset + viewportWidth
        val index = columnStraddling(edge)
        if (index < 0) return 0.0
        return capped(edge - starts[index], viewportWidth)
    }

    /**
     * Where the next page begins, or null when this spread has no next page in
     * that direction and the caller must hand the turn to the navigator — which
     * is what moves between chapters.
     *
     * @param movesLeft true when turning that way decreases the offset, which
     *   for a right-to-left publication is what "forward" means.
     */
    fun pageTarget(
        offset: Double,
        viewportWidth: Double,
        movesLeft: Boolean,
        maximumOffset: Double
    ): Double? {
        if (!isUsable || viewportWidth <= 0.0 || !offset.isFinite()) return null
        if (maximumOffset < 0.0 || !maximumOffset.isFinite()) return null

        val raw = if (movesLeft) {
            // The leftmost column shown whole on this page; the next page ends
            // where it ends, so it is the rightmost whole column there.
            val index = firstColumnStartingAtOrAfter(offset)
            if (index < 0 || ends[index] > offset + viewportWidth + TOLERANCE) return null
            ends[index] - viewportWidth
        } else {
            // Mirror image: the rightmost column shown whole becomes the
            // leftmost whole column of the page before it.
            val index = lastColumnEndingAtOrBefore(offset + viewportWidth)
            if (index < 0 || starts[index] < offset - TOLERANCE) return null
            starts[index]
        }

        val target = min(max(raw, 0.0), maximumOffset)
        // A page holding a single whole column cannot both show it and hold it
        // back. Standing still would swallow the turn, so say so and let the
        // navigator move instead.
        if (abs(target - offset) <= MINIMUM_PAGE_MOVEMENT) return null
        if (movesLeft && target > offset) return null
        if (!movesLeft && target < offset) return null
        return target
    }

    /** The last column index whose box contains [x] strictly inside it. */
    private fun columnStraddling(x: Double): Int {
        // The columns are sorted and effectively disjoint, so the candidate is
        // the last one starting at or before x.
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (starts[middle] <= x - TOLERANCE) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (candidate < 0) return -1
        return if (ends[candidate] >= x + TOLERANCE) candidate else -1
    }

    private fun firstColumnStartingAtOrAfter(x: Double): Int {
        var low = 0
        var high = starts.size - 1
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (starts[middle] >= x - TOLERANCE) {
                candidate = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return candidate
    }

    // Boxes are sorted by start and do not overlap, so their ends are ordered
    // too and the same binary search works on either edge.
    private fun lastColumnEndingAtOrBefore(x: Double): Int {
        var low = 0
        var high = ends.size - 1
        var candidate = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (ends[middle] <= x + TOLERANCE) {
                candidate = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return candidate
    }

    /**
     * A gutter is a sliver, never a swathe. Anything wider than this is the
     * page edge landing in white space rather than in a column, and covering it
     * would hide text instead of a fragment of one.
     */
    private fun capped(width: Double, viewportWidth: Double): Double {
        if (!width.isFinite() || width <= MINIMUM_PAGE_MOVEMENT) return 0.0
        if (width > viewportWidth * MAXIMUM_GUTTER_FRACTION) return 0.0
        return width
    }

    private companion object {
        const val TOLERANCE = 0.5
        const val MINIMUM_COLUMN_WIDTH = 0.5
        const val MINIMUM_PAGE_MOVEMENT = 0.5
        const val MAXIMUM_GUTTER_FRACTION = 0.5
    }
}
