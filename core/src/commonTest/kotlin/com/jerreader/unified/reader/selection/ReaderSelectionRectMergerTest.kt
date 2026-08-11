package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSelectionRectMergerTest {

    @Test
    fun `fragments on one line become a single tile`() {
        val fragments = listOf(
            ReaderRect(10.0, 100.0, 40.0, 18.0),
            ReaderRect(50.0, 100.0, 35.0, 18.0),
            ReaderRect(85.5, 100.0, 20.0, 18.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        assertEquals(1, merged.size)
        assertEquals(10.0, merged[0].left)
        assertEquals(105.5, merged[0].right)
    }

    @Test
    fun `a real gap within a line stays two tiles`() {
        val fragments = listOf(
            ReaderRect(10.0, 100.0, 40.0, 18.0),
            ReaderRect(120.0, 100.0, 40.0, 18.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        assertEquals(2, merged.size)
    }

    @Test
    fun `separate lines stay separate`() {
        val fragments = listOf(
            ReaderRect(10.0, 100.0, 60.0, 18.0),
            ReaderRect(10.0, 122.0, 60.0, 18.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        assertEquals(2, merged.size)
    }

    @Test
    fun `vertical text groups by column rather than by row`() {
        // Two columns of a vertical-rl page: the same y range, different x.
        val fragments = listOf(
            ReaderRect(200.0, 40.0, 20.0, 90.0),
            ReaderRect(200.0, 130.0, 20.0, 70.0),
            ReaderRect(174.0, 40.0, 20.0, 120.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.VERTICAL)

        assertEquals(2, merged.size)
        // The right-hand column's two runs are contiguous and collapse into one.
        val rightColumn = merged.first { it.left >= 200.0 }
        assertEquals(40.0, rightColumn.top)
        assertEquals(200.0, rightColumn.bottom)
    }

    @Test
    fun `a ruby annotation box that covers its own base is dropped`() {
        // The `ruby` element reports a box enclosing the kanji band beneath it.
        val base = ReaderRect(10.0, 100.0, 40.0, 18.0)
        val enclosing = ReaderRect(9.0, 99.0, 42.0, 20.0)

        val merged = ReaderSelectionRectMerger.merge(
            listOf(enclosing, base),
            ReaderWritingMode.HORIZONTAL
        )

        assertEquals(1, merged.size)
        // The survivor is the enclosing box; what matters is that the pixels
        // under it are covered exactly once.
        assertEquals(1, merged.count { it.intersects(base) })
    }

    @Test
    fun `overlapping lines meet at the midpoint so no pixel is painted twice`() {
        // A ruby base reserves annotation room, so its line bleeds into the next.
        val fragments = listOf(
            ReaderRect(10.0, 100.0, 60.0, 26.0),
            ReaderRect(10.0, 120.0, 60.0, 18.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        assertEquals(2, merged.size)
        val upper = merged.minByOrNull { it.top }!!
        val lower = merged.maxByOrNull { it.top }!!
        assertEquals(upper.bottom, lower.top, absoluteTolerance = 1e-9)
        assertTrue(upper.intersection(lower) == null)
    }

    @Test
    fun `two near-identical boxes leave exactly one survivor`() {
        // Sub-pixel-different overlapping boxes are what ruby and fragment
        // geometry actually produce. Each one contains the other once the
        // containment slack is applied, so a rule that is not a strict order
        // drops both and the whole line disappears.
        val slightlySmaller = ReaderRect(0.1, 0.1, 9.8, 9.8)
        val slightlyLarger = ReaderRect(0.0, 0.0, 10.0, 10.0)

        val merged = ReaderSelectionRectMerger.merge(
            listOf(slightlySmaller, slightlyLarger),
            ReaderWritingMode.HORIZONTAL
        )

        assertEquals(1, merged.size, "the selection must not vanish")
    }

    @Test
    fun `a ruby word does not break the line it sits in`() {
        // Measured in WKWebView from
        // 「あばらやに行こう、といいだしたのは<ruby>翔<rt>しょう</rt></ruby>
        //  <ruby>太<rt>た</rt></ruby>だった。」 at 20px/line-height 2.
        // しょう is wider than 翔, so the ruby element reserves the reading's
        // advance and the two base glyphs land 5px apart — far past
        // RUN_GAP_TOLERANCE. Painting that gives the reader a highlight that
        // breaks open in the middle of a word.
        val fragments = listOf(
            ReaderRect(16.0, 32.0, 340.0, 21.0),   // あばらや…のは
            ReaderRect(356.0, 32.0, 20.0, 21.0),   // 翔
            ReaderRect(381.0, 32.0, 20.0, 21.0),   // 太
            ReaderRect(351.0, 20.0, 30.0, 12.0),   // しょう
            ReaderRect(381.0, 20.0, 20.0, 12.0)    // た
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        val baseLine = merged.filter { it.height > 15.0 }
        assertEquals(1, baseLine.size, "the base line broke apart around the ruby")
        assertEquals(16.0, baseLine[0].left)
        assertEquals(401.0, baseLine[0].right)
        // The readings still paint as their own band above the text.
        assertTrue(merged.any { it.top < 32.0 }, "the ruby readings lost their tile")
    }

    @Test
    fun `a vertical ruby column does not break the line it sits in`() {
        // The same page rotated: lines advance downwards, the reading band sits
        // beside the column rather than above it.
        val fragments = listOf(
            ReaderRect(300.0, 16.0, 21.0, 340.0),
            ReaderRect(300.0, 356.0, 21.0, 20.0),
            ReaderRect(300.0, 381.0, 21.0, 20.0),
            ReaderRect(321.0, 351.0, 12.0, 30.0),
            ReaderRect(321.0, 381.0, 12.0, 20.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.VERTICAL)

        val baseColumn = merged.filter { it.width > 15.0 }
        assertEquals(1, baseColumn.size, "the base column broke apart around the ruby")
        assertEquals(16.0, baseColumn[0].top)
        assertEquals(401.0, baseColumn[0].bottom)
    }

    @Test
    fun `the line below never bridges a gap in the line above`() {
        // Tight leading puts two body lines' bands in contact. Only a band that
        // is distinctly thinner than the text counts as an annotation, so the
        // full-width line below must not close a real gap in the line above.
        val fragments = listOf(
            ReaderRect(10.0, 100.0, 40.0, 20.0),
            ReaderRect(200.0, 100.0, 40.0, 20.0),
            ReaderRect(10.0, 120.0, 300.0, 20.0)
        )

        val merged = ReaderSelectionRectMerger.merge(fragments, ReaderWritingMode.HORIZONTAL)

        val upper = merged.filter { it.top < 115.0 }
        assertEquals(2, upper.size, "a real gap on the upper line was closed")
    }

    @Test
    fun `degenerate fragments are discarded`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderRect(10.0, 100.0, 0.2, 18.0),
                ReaderRect(20.0, 100.0, 40.0, 0.1)
            ),
            ReaderWritingMode.HORIZONTAL
        )

        assertTrue(merged.isEmpty())
    }
}
