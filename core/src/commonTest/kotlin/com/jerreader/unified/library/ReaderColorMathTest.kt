package com.jerreader.unified.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderColorMathTest {

    @Test
    fun `known colours convert both ways`() {
        assertEquals(ReaderHsv(0.0, 1.0, 1.0), ReaderColorMath.hsvOf("#FF0000"))
        assertEquals(ReaderHsv(120.0, 1.0, 1.0), ReaderColorMath.hsvOf("#00FF00"))
        assertEquals(ReaderHsv(240.0, 1.0, 1.0), ReaderColorMath.hsvOf("#0000FF"))
        assertEquals(ReaderHsv(0.0, 0.0, 1.0), ReaderColorMath.hsvOf("#FFFFFF"))
        assertEquals(ReaderHsv(0.0, 0.0, 0.0), ReaderColorMath.hsvOf("#000000"))

        assertEquals("#FF0000", ReaderColorMath.hexOf(0.0, 1.0, 1.0))
        assertEquals("#00FF00", ReaderColorMath.hexOf(120.0, 1.0, 1.0))
        assertEquals("#0000FF", ReaderColorMath.hexOf(240.0, 1.0, 1.0))
        assertEquals("#FFFFFF", ReaderColorMath.hexOf(0.0, 0.0, 1.0))
        assertEquals("#000000", ReaderColorMath.hexOf(0.0, 0.0, 0.0))
    }

    @Test
    fun `every colour survives a round trip`() {
        // Reopening the picker must not shift the page by a shade. Stepping
        // every channel by 17 walks all 16 values of each nibble, so every
        // sextant of the hue wheel and both degenerate cases (grey, black) are
        // covered without running all 16.7 million.
        var checked = 0
        for (red in 0..255 step 17) {
            for (green in 0..255 step 17) {
                for (blue in 0..255 step 17) {
                    val hex = ReaderPageBackground.toRgbHex(
                        (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()
                    )
                    val hsv = ReaderColorMath.hsvOf(hex)
                    assertTrue(hsv != null, "$hex did not parse")
                    assertEquals(hex, ReaderColorMath.hexOf(hsv), "round trip changed $hex")
                    checked += 1
                }
            }
        }
        assertEquals(16 * 16 * 16, checked)
    }

    @Test
    fun `page colours survive a round trip`() {
        for (theme in ReaderThemeOption.entries) {
            val hex = ReaderPageBackground.toRgbHex(ReaderPageBackground.themeArgb(theme))
            assertEquals(hex, ReaderColorMath.hexOf(ReaderColorMath.hsvOf(hex)!!))
        }
    }

    @Test
    fun `out of range input is wrapped and clamped rather than refused`() {
        assertEquals("#FF0000", ReaderColorMath.hexOf(360.0, 1.0, 1.0))
        assertEquals("#FF0000", ReaderColorMath.hexOf(-360.0, 2.0, 4.0))
        assertEquals("#000000", ReaderColorMath.hexOf(120.0, 1.0, -1.0))
        assertEquals(0.0, ReaderColorMath.wrappedHue(Double.NaN))
        assertEquals(350.0, ReaderColorMath.wrappedHue(-10.0))
    }

    @Test
    fun `a malformed colour has no hsv form`() {
        assertNull(ReaderColorMath.hsvOf(""))
        assertNull(ReaderColorMath.hsvOf("#FA0"))
        assertNull(ReaderColorMath.hsvOf("#FFAA0G"))
    }
}
