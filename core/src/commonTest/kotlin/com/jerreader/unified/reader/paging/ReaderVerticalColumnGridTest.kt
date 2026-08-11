package com.jerreader.unified.reader.paging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The property these exist for is the one the reader reported twice: on a
 * `vertical-rl` page, no column may be *shown* cut in half, and none may be
 * lost between two pages either. Every case below is stated in terms of what is
 * on screen — which columns are whole, which slivers are covered — rather than
 * in terms of offsets, because that is what "最旁边一列可能一部分在下一页" means.
 */
class ReaderVerticalColumnGridTest {

    private val viewport = 393.0

    /** Sub-point slivers the grid, and the eye, both treat as nothing. */
    private val FLUSH = 0.5

    /**
     * A run of [count] columns [width] wide ending at [rightEdge], laid out
     * right to left the way `vertical-rl` does. [gapEvery] inserts a paragraph
     * gap, which is what stops a real page from being a uniform grid.
     */
    private fun columns(
        rightEdge: Double,
        count: Int,
        width: Double,
        gapEvery: Int = 0,
        gap: Double = 0.0
    ): List<Double> {
        val edges = ArrayList<Double>(count * 2)
        var end = rightEdge
        for (index in 0 until count) {
            if (gapEvery > 0 && index > 0 && index % gapEvery == 0) end -= gap
            edges.add(end - width)
            edges.add(end)
            end -= width
        }
        return edges
    }

    /** The columns of [grid] that the page at [offset] shows complete. */
    private fun wholeColumns(
        edges: List<Double>,
        offset: Double,
        viewportWidth: Double = viewport
    ): List<Pair<Double, Double>> =
        edges.chunked(2)
            .map { it[0] to it[1] }
            .filter { it.first >= offset - 1e-9 && it.second <= offset + viewportWidth + 1e-9 }
            .sortedBy { it.first }

    /**
     * What the reader is left looking at: every column of which the page shows
     * a *part* — more than a hairline, less than the whole thing. This must
     * always be empty.
     *
     * The half-point of slack is the same one the grid treats as flush. A line
     * box is taller than its ink by the half-leading, so half a point of one
     * carries no glyph at all; covering it would be a hairline of paper drawn
     * over paper, and holding a whole column back for it would cost a column of
     * every page for nothing.
     */
    private fun visibleSplitColumns(
        edges: List<Double>,
        grid: ReaderVerticalColumnGrid,
        offset: Double,
        viewportWidth: Double = viewport
    ): List<Pair<Double, Double>> {
        val head = offset + grid.leadingGutterWidth(offset, viewportWidth)
        val tail = offset + viewportWidth - grid.trailingGutterWidth(offset, viewportWidth)
        return edges.chunked(2)
            .map { it[0] to it[1] }
            .filter { column ->
                val shown = minOf(column.second, tail) - maxOf(column.first, head)
                val whole = column.second - column.first
                shown > FLUSH && shown < whole - FLUSH
            }
    }

    @Test
    fun `a page covers the part-columns at both edges and nothing else`() {
        // 393 over 24pt columns: 16 whole columns and 9pt left over, which the
        // page splits between its two edges wherever it happens to sit.
        val edges = columns(rightEdge = 4_000.0, count = 400, width = 24.0)
        val grid = ReaderVerticalColumnGrid(edges)
        var offset = 100.0
        while (offset <= 3_500.0) {
            assertEquals(
                emptyList(),
                visibleSplitColumns(edges, grid, offset),
                "offset=$offset shows a column cut in half"
            )
            val head = grid.leadingGutterWidth(offset, viewport)
            val tail = grid.trailingGutterWidth(offset, viewport)
            assertTrue(head < 24.0, "offset=$offset covers a whole column at the left")
            assertTrue(tail < 24.0, "offset=$offset covers a whole column at the right")
            offset += 0.25
        }
    }

    /**
     * The case the uniform-grid version could not answer. Paragraph spacing is
     * a gap along the block axis in `vertical-rl`, so column edges within a
     * single screen do not share one rhythm.
     */
    @Test
    fun `paragraph gaps do not fool the gutters`() {
        val edges = columns(
            rightEdge = 4_000.0,
            count = 400,
            width = 26.0,
            gapEvery = 7,
            gap = 11.0
        )
        val grid = ReaderVerticalColumnGrid(edges)
        var offset = 100.0
        while (offset <= 3_000.0) {
            assertEquals(
                emptyList(),
                visibleSplitColumns(edges, grid, offset),
                "offset=$offset shows a column cut in half"
            )
            offset += 0.25
        }
    }

    /** A heading's line height differs from the body's within the same page. */
    @Test
    fun `columns of mixed widths are still never split`() {
        val edges = ArrayList<Double>()
        var end = 4_000.0
        var index = 0
        while (end > 200.0) {
            val width = when (index % 5) {
                0 -> 41.5
                1, 2 -> 23.0
                3 -> 23.5
                else -> 29.75
            }
            edges.add(end - width)
            edges.add(end)
            end -= width
            index += 1
        }
        val grid = ReaderVerticalColumnGrid(edges)
        var offset = 300.0
        while (offset <= 3_400.0) {
            assertEquals(
                emptyList(),
                visibleSplitColumns(edges, grid, offset),
                "offset=$offset shows a column cut in half"
            )
            offset += 0.25
        }
    }

    @Test
    fun `an edge landing in the gap between paragraphs covers nothing`() {
        // Columns end at 1000 and the next starts at 980: the 20pt between them
        // is white space, and hiding white space is not this object's business.
        val grid = ReaderVerticalColumnGrid(
            listOf(1_000.0, 1_024.0, 956.0, 980.0, 932.0, 956.0)
        )
        assertEquals(0.0, grid.leadingGutterWidth(990.0, viewport), 1e-9)
    }

    @Test
    fun `a page turn repeats exactly one whole column forwards and back`() {
        // Long enough that thirty turns stay inside the spread; a turn that
        // runs off the end is the subject of its own test.
        val edges = columns(
            rightEdge = 20_000.0,
            count = 700,
            width = 26.0,
            gapEvery = 5,
            gap = 9.0
        )
        val grid = ReaderVerticalColumnGrid(edges)
        val maximumOffset = 20_000.0 - viewport

        var offset = maximumOffset
        repeat(30) { page ->
            val shown = wholeColumns(edges, offset)
            assertTrue(shown.size > 5, "page $page shows only ${shown.size} whole columns")

            val next = assertNotNull(
                grid.pageTarget(offset, viewport, movesLeft = true, maximumOffset = maximumOffset),
                "page $page had no next page to turn to"
            )
            assertTrue(next < offset, "page $page did not move forwards")
            val nextShown = wholeColumns(edges, next)

            val repeated = shown.filter { it in nextShown }
            assertEquals(
                listOf(shown.first()),
                repeated,
                "page $page repeated $repeated instead of exactly its leftmost column"
            )
            // Nothing may fall between the two pages either.
            val skipped = edges.chunked(2)
                .map { it[0] to it[1] }
                .filter { it.second <= shown.first().second && it.first >= next }
                .filter { it !in nextShown }
            assertEquals(emptyList(), skipped, "page $page skipped $skipped")

            // And turning back shows the page that was just left. Not
            // necessarily at the same offset: coming back lands on a column
            // boundary, so a page first reached mid-column — from a bookmark,
            // or Readium's own progression scroll — is flush afterwards. What
            // matters is that nothing that was readable has gone.
            val back = assertNotNull(
                grid.pageTarget(next, viewport, movesLeft = false, maximumOffset = maximumOffset),
                "page $page could not be returned to"
            )
            val backShown = wholeColumns(edges, back)
            assertEquals(
                emptyList(),
                shown.filter { it !in backShown },
                "page $page lost ${shown.filter { it !in backShown }} on the way back"
            )
            offset = next
        }
    }

    @Test
    fun `the far end of a spread hands the turn to the navigator`() {
        val edges = columns(rightEdge = 500.0, count = 20, width = 24.0)
        val grid = ReaderVerticalColumnGrid(edges)
        val maximumOffset = 500.0 - viewport
        assertNull(
            grid.pageTarget(0.0, viewport, movesLeft = true, maximumOffset = maximumOffset),
            "there is nothing to the left of the start of the content"
        )
        assertNull(
            grid.pageTarget(
                maximumOffset,
                viewport,
                movesLeft = false,
                maximumOffset = maximumOffset
            ),
            "there is nothing to the right of the end of the content"
        )
    }

    @Test
    fun `a page too narrow to hold two columns hands the turn over`() {
        val grid = ReaderVerticalColumnGrid(columns(rightEdge = 400.0, count = 8, width = 30.0))
        assertNull(grid.pageTarget(300.0, 34.0, movesLeft = true, maximumOffset = 360.0))
    }

    @Test
    fun `a spread that measures nothing usable reports so instead of guessing`() {
        for (nothing in listOf(
            emptyList(),
            listOf(12.0),
            // Zero-width and inverted boxes are WebKit reporting a box that is
            // not a column; they are dropped rather than sorted into the grid.
            listOf(10.0, 10.0, 30.0, 20.0),
            listOf(Double.NaN, 20.0, 40.0, Double.POSITIVE_INFINITY)
        )) {
            val grid = ReaderVerticalColumnGrid(nothing)
            assertTrue(!grid.isUsable, "$nothing must not pass for a measured page")
            assertEquals(0.0, grid.leadingGutterWidth(100.0, viewport), 1e-9)
            assertEquals(0.0, grid.trailingGutterWidth(100.0, viewport), 1e-9)
            assertNull(grid.pageTarget(100.0, viewport, true, 1_000.0))
        }
    }

    @Test
    fun `boxes arrive in whatever order the document walk produced them`() {
        val ordered = columns(rightEdge = 4_000.0, count = 60, width = 24.0)
        val shuffled = ordered.chunked(2).reversed().flatten()
        val fromOrdered = ReaderVerticalColumnGrid(ordered)
        val fromShuffled = ReaderVerticalColumnGrid(shuffled)
        assertEquals(fromOrdered.columnCount, fromShuffled.columnCount)
        var offset = 2_600.0
        while (offset < 3_500.0) {
            assertEquals(
                fromOrdered.leadingGutterWidth(offset, viewport),
                fromShuffled.leadingGutterWidth(offset, viewport),
                1e-9
            )
            assertEquals(
                fromOrdered.trailingGutterWidth(offset, viewport),
                fromShuffled.trailingGutterWidth(offset, viewport),
                1e-9
            )
            offset += 1.0
        }
    }

    @Test
    fun `no plausible page ever shows a split column`() {
        // Every phone and tablet width against every column width the reader's
        // font sizes produce, with and without paragraph spacing.
        var checked = 0
        var width = 280.0
        while (width <= 1_200.0) {
            var column = 14.0
            while (column < width / 2) {
                for (gap in listOf(0.0, 7.5)) {
                    val edges = columns(
                        rightEdge = width * 6,
                        count = (width * 6 / column).toInt(),
                        width = column,
                        gapEvery = if (gap > 0) 6 else 0,
                        gap = gap
                    )
                    val grid = ReaderVerticalColumnGrid(edges)
                    for (offset in listOf(width * 1.5, width * 3 + 0.5, width * 4 + 11.25)) {
                        val head = grid.leadingGutterWidth(offset, width)
                        val tail = grid.trailingGutterWidth(offset, width)
                        assertTrue(head < column + gap, "width=$width column=$column head=$head")
                        assertTrue(tail < column + gap, "width=$width column=$column tail=$tail")
                        assertEquals(
                            emptyList(),
                            visibleSplitColumns(edges, grid, offset, width),
                            "width=$width column=$column gap=$gap offset=$offset"
                        )
                        checked += 1
                    }
                }
                column += 2.0
            }
            width += 20.0
        }
        assertTrue(checked > 3_000, "swept only $checked pages")
    }

    @Test
    fun `turning pages walks the whole spread without losing a column`() {
        val edges = columns(rightEdge = 5_000.0, count = 190, width = 24.5, gapEvery = 9, gap = 6.0)
        val grid = ReaderVerticalColumnGrid(edges)
        val maximumOffset = 5_000.0 - viewport
        val seen = HashSet<Double>()
        var offset = maximumOffset
        var turns = 0
        while (turns < 500) {
            wholeColumns(edges, offset).forEach { seen.add(it.first) }
            val next = grid.pageTarget(offset, viewport, true, maximumOffset) ?: break
            offset = next
            turns += 1
        }
        val missed = edges.chunked(2).map { it[0] to it[1] }.filter { it.first !in seen }
        assertEquals(emptyList(), missed, "reading through the spread never showed $missed")
        assertTrue(turns > 10, "the walk stopped after only $turns turns")
    }
}
