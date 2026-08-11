package com.jerreader.unified.reader.selection

import com.jerreader.unified.reader.geometry.ReaderRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderSpeechHighlightGeometryTest {

    private val line = listOf(
        ReaderRect(0.0, 0.0, 100.0, 20.0),
        ReaderRect(0.0, 24.0, 100.0, 20.0)
    )

    @Test
    fun `no highlight paints nothing`() {
        assertTrue(ReaderSpeechHighlightGeometry.rectangles(line, null).isEmpty())
    }

    @Test
    fun `the first half of the utterance covers only the first tile`() {
        val spoken = ReaderSpeechHighlightGeometry.rectangles(
            line,
            ReaderSpeechHighlight(0.0, 0.5),
            ReaderWritingMode.HORIZONTAL
        )

        assertEquals(1, spoken.size)
        assertEquals(0.0, spoken[0].top)
        assertEquals(100.0, spoken[0].width)
    }

    @Test
    fun `a partial tile is cut along the line axis`() {
        val spoken = ReaderSpeechHighlightGeometry.rectangles(
            line,
            ReaderSpeechHighlight(0.0, 0.25),
            ReaderWritingMode.HORIZONTAL
        )

        assertEquals(1, spoken.size)
        assertEquals(50.0, spoken[0].width, absoluteTolerance = 1e-9)
        assertEquals(20.0, spoken[0].height)
    }

    @Test
    fun `vertical text is cut down the tile instead of across it`() {
        val column = listOf(ReaderRect(0.0, 0.0, 20.0, 100.0))

        val spoken = ReaderSpeechHighlightGeometry.rectangles(
            column,
            ReaderSpeechHighlight(0.0, 0.5),
            ReaderWritingMode.VERTICAL
        )

        assertEquals(1, spoken.size)
        assertEquals(20.0, spoken[0].width)
        assertEquals(50.0, spoken[0].height, absoluteTolerance = 1e-9)
    }

    @Test
    fun `a tall narrow tile is treated as vertical when no mode is given`() {
        val column = listOf(ReaderRect(0.0, 0.0, 20.0, 100.0))

        val spoken = ReaderSpeechHighlightGeometry.rectangles(
            column,
            ReaderSpeechHighlight(0.0, 0.5)
        )

        assertEquals(50.0, spoken[0].height, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the whole utterance covers every tile`() {
        val spoken = ReaderSpeechHighlightGeometry.rectangles(
            line,
            ReaderSpeechHighlight(0.0, 1.0),
            ReaderWritingMode.HORIZONTAL
        )

        assertEquals(2, spoken.size)
    }
}

class ReaderSelectionGestureGateTest {

    @Test
    fun `a tap right after a native selection is suppressed`() {
        val gate = ReaderSelectionGestureGate()

        gate.noteNativeSelection(uptimeMillis = 1_000)

        assertTrue(
            gate.shouldSuppressTap(1_200),
            "the trailing tap would replace a hand-made selection with the whole sentence"
        )
    }

    @Test
    fun `a tap after the window passes is honoured`() {
        val gate = ReaderSelectionGestureGate()

        gate.noteNativeSelection(uptimeMillis = 1_000)

        assertFalse(gate.shouldSuppressTap(1_000 + ReaderSelectionGestureGate.DEFAULT_SUPPRESSION_MILLIS + 1))
    }

    @Test
    fun `an expired gate stops suppressing permanently`() {
        val gate = ReaderSelectionGestureGate()
        gate.noteNativeSelection(uptimeMillis = 0)

        assertFalse(gate.shouldSuppressTap(9_000))
        assertFalse(gate.shouldSuppressTap(1))
    }

    @Test
    fun `a second selection extends the window`() {
        val gate = ReaderSelectionGestureGate(suppressionMillis = 1_000)

        gate.noteNativeSelection(uptimeMillis = 0)
        gate.noteNativeSelection(uptimeMillis = 800)

        assertTrue(gate.shouldSuppressTap(1_500))
    }

    @Test
    fun `reset clears the window`() {
        val gate = ReaderSelectionGestureGate()
        gate.noteNativeSelection(uptimeMillis = 1_000)

        gate.reset()

        assertFalse(gate.shouldSuppressTap(1_100))
    }
}
