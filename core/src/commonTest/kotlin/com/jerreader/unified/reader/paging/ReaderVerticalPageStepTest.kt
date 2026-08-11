package com.jerreader.unified.reader.paging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderVerticalPageStepTest {

    @Test
    fun `a measured column is held back from the step`() {
        assertEquals(
            353.0,
            ReaderVerticalPageStep.advance(viewportWidth = 393.0, columnAdvance = 40.0),
            0.0001
        )
    }

    @Test
    fun `an unmeasured column falls back to a full viewport`() {
        assertEquals(
            393.0,
            ReaderVerticalPageStep.advance(viewportWidth = 393.0, columnAdvance = null),
            0.0001
        )
    }

    @Test
    fun `an implausibly wide column falls back rather than crawling`() {
        // Half the viewport or wider: a bad measurement, not a real column.
        assertEquals(
            393.0,
            ReaderVerticalPageStep.advance(viewportWidth = 393.0, columnAdvance = 200.0),
            0.0001
        )
        assertEquals(
            393.0,
            ReaderVerticalPageStep.advance(viewportWidth = 393.0, columnAdvance = 196.5),
            0.0001
        )
    }

    @Test
    fun `degenerate measurements never produce a zero step`() {
        for (bad in listOf(0.0, 1.0, -12.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                393.0,
                ReaderVerticalPageStep.advance(viewportWidth = 393.0, columnAdvance = bad),
                0.0001,
                "columnAdvance=$bad"
            )
        }
    }

    @Test
    fun `every plausible viewport and column keeps a whole column of overlap`() {
        // Phone portrait through tablet landscape, at every font size the
        // reader offers: whenever the measurement is usable, the boundary must
        // never fall inside a column.
        var checked = 0
        var viewport = 280.0
        while (viewport <= 1200.0) {
            var column = 14.0
            while (column < viewport / 2) {
                val step = ReaderVerticalPageStep.advance(viewport, column)
                assertTrue(step > 0.0, "viewport=$viewport column=$column step=$step")
                assertTrue(step <= viewport, "viewport=$viewport column=$column step=$step")
                assertTrue(
                    ReaderVerticalPageStep.keepsEveryColumnWhole(viewport, column, step),
                    "viewport=$viewport column=$column step=$step"
                )
                checked += 1
                column += 0.5
            }
            viewport += 1.0
        }
        assertTrue(checked > 100_000, "swept only $checked pairs")
    }

    @Test
    fun `a full viewport step is exactly what loses a column`() {
        // The behaviour being replaced, stated as a failing property so the
        // regression is legible: stepping a whole viewport leaves no overlap.
        assertFalse(
            ReaderVerticalPageStep.keepsEveryColumnWhole(
                viewportWidth = 393.0,
                columnAdvance = 40.0,
                step = 393.0
            )
        )
    }

    @Test
    fun `a zero viewport asks for no movement`() {
        assertEquals(0.0, ReaderVerticalPageStep.advance(0.0, 40.0), 0.0001)
        assertEquals(0.0, ReaderVerticalPageStep.advance(-10.0, 40.0), 0.0001)
    }
}
