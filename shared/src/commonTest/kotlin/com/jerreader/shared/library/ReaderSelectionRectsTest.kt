package com.jerreader.shared.library

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderSelectionRectsTest {

    private fun overlaps(first: ReaderSelectionRect, second: ReaderSelectionRect): Boolean {
        val horizontal = min(first.right, second.right) - max(first.x, second.x)
        val vertical = min(first.bottom, second.bottom) - max(first.y, second.y)
        return horizontal > 0.01 && vertical > 0.01
    }

    private fun assertNoOverlap(rects: List<ReaderSelectionRect>) {
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                assertTrue(
                    !overlaps(rects[i], rects[j]),
                    "rect $i ${rects[i]} overlaps rect $j ${rects[j]}"
                )
            }
        }
    }

    @Test
    fun `horizontal fragments on one line become a single run`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 100.0, width = 80.0, height = 24.0),
                ReaderSelectionRect(x = 100.0, y = 100.0, width = 60.0, height = 24.0),
                ReaderSelectionRect(x = 159.0, y = 101.0, width = 40.0, height = 22.0)
            ),
            vertical = false
        )

        assertEquals(1, merged.size)
        assertEquals(20.0, merged[0].x)
        assertEquals(199.0, merged[0].right)
        assertNoOverlap(merged)
    }

    @Test
    fun `a ruby base box no longer paints over the plain text beside it`() {
        // The ruby base reports a taller box that starts above the line and
        // overruns the fragment to its left by a fraction of a pixel.
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 100.0, width = 80.0, height = 24.0),
                ReaderSelectionRect(x = 99.4, y = 92.0, width = 32.0, height = 32.0),
                ReaderSelectionRect(x = 131.0, y = 100.0, width = 50.0, height = 24.0)
            ),
            vertical = false
        )

        assertEquals(1, merged.size)
        assertEquals(20.0, merged[0].x)
        assertEquals(181.0, merged[0].right)
        assertNoOverlap(merged)
    }

    @Test
    fun `stacked horizontal lines stay separate and never overlap`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 100.0, width = 200.0, height = 24.0),
                // Second line, lifted by a ruby base so it reaches into the first.
                ReaderSelectionRect(x = 20.0, y = 118.0, width = 40.0, height = 30.0),
                ReaderSelectionRect(x = 60.0, y = 126.0, width = 120.0, height = 24.0)
            ),
            vertical = false
        )

        assertEquals(2, merged.size)
        assertNoOverlap(merged)
        assertTrue(merged.all { it.height > 0.0 })
    }

    @Test
    fun `vertical columns merge down the y axis`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 500.0, y = 60.0, width = 30.0, height = 180.0),
                ReaderSelectionRect(x = 500.0, y = 239.0, width = 30.0, height = 90.0),
                // Ruby base in the same column: wider box, same run.
                ReaderSelectionRect(x = 494.0, y = 200.0, width = 40.0, height = 60.0)
            ),
            vertical = true
        )

        assertEquals(1, merged.size)
        assertEquals(60.0, merged[0].y)
        assertEquals(329.0, merged[0].bottom)
        assertNoOverlap(merged)
    }

    @Test
    fun `vertical columns are separate lines and never overlap`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 500.0, y = 60.0, width = 30.0, height = 300.0),
                // The next column to the left, widened by a ruby annotation.
                ReaderSelectionRect(x = 466.0, y = 60.0, width = 38.0, height = 120.0),
                ReaderSelectionRect(x = 470.0, y = 180.0, width = 30.0, height = 140.0)
            ),
            vertical = true
        )

        assertEquals(2, merged.size)
        assertNoOverlap(merged)
        assertTrue(merged.all { it.width > 0.0 })
    }

    @Test
    fun `a run broken by an inline image stays two runs`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 100.0, width = 60.0, height = 24.0),
                ReaderSelectionRect(x = 140.0, y = 100.0, width = 60.0, height = 24.0)
            ),
            vertical = false
        )

        assertEquals(2, merged.size)
        assertNoOverlap(merged)
    }

    @Test
    fun `layout artefacts are dropped`() {
        val merged = ReaderSelectionRectMerger.merge(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 100.0, width = 0.2, height = 24.0),
                ReaderSelectionRect(x = 30.0, y = 100.0, width = 60.0, height = 0.1)
            ),
            vertical = false
        )

        assertEquals(0, merged.size)
    }
}
