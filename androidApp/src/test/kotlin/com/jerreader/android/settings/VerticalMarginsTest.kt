package com.jerreader.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下边距 (2.0.1): a multiple of the 14dp gap the reader always had. The
 * default has to be exactly 1, so a reader who never opens the setting sees
 * the page where it was; iOS clamps and labels the value the same way
 * (`ReaderInterfaceDefaults`).
 */
class VerticalMarginsTest {

    @Test
    fun defaultKeepsTheGapThatShipped() {
        assertEquals(1f, AndroidAppPreferences().verticalMargins, 0f)
        assertEquals(1f, clampedVerticalMargins(1f), 0f)
    }

    @Test
    fun storedValuesAreClampedAndRoundedToATenth() {
        assertEquals(0f, clampedVerticalMargins(-3f), 0f)
        assertEquals(4f, clampedVerticalMargins(99f), 0f)
        assertEquals(2.3f, clampedVerticalMargins(2.34f), 0.0001f)
        assertEquals(0.5f, clampedVerticalMargins(0.46f), 0.0001f)
        assertEquals(1f, clampedVerticalMargins(Float.NaN), 0f)
        assertEquals(1f, clampedVerticalMargins(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, VERTICAL_MARGINS_RANGE.start, 0f)
        assertEquals(4f, VERTICAL_MARGINS_RANGE.endInclusive, 0f)
    }

    @Test
    fun labelReadsLikeTheSideMargins() {
        assertEquals("窄", verticalMarginsLabel(0f))
        assertEquals("窄", verticalMarginsLabel(0.7f))
        assertEquals("标准", verticalMarginsLabel(0.8f))
        assertEquals("标准", verticalMarginsLabel(1f))
        assertEquals("标准", verticalMarginsLabel(1.5f))
        assertEquals("宽", verticalMarginsLabel(1.6f))
        assertEquals("宽", verticalMarginsLabel(4f))
    }
}
