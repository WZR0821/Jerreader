package com.jerreader.unified.reader.selection

import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderThemeOption
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReaderSelectionPaletteTest {

    private fun alpha(argb: Long): Int = ((argb shr 24) and 0xFF).toInt()

    @Test
    fun `a dark page gets a stronger fill than a light one`() {
        val light = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(theme = ReaderThemeOption.LIGHT)
        )
        val dark = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(theme = ReaderThemeOption.DARK)
        )

        assertTrue(alpha(dark.fillArgb) > alpha(light.fillArgb))
    }

    @Test
    fun `the spoken fill is more opaque than the resting fill`() {
        val palette = ReaderSelectionVisualStyle.palette(ReaderAppearance())

        assertTrue(
            alpha(palette.spokenFillArgb) > alpha(palette.fillArgb),
            "read-aloud has to stand out from the rest of the selection"
        )
    }

    @Test
    fun `an accent too close to the page is nudged until it can be seen`() {
        // Near-white accent on the default white page: invisible at 30% alpha.
        val washedOut = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(
                theme = ReaderThemeOption.LIGHT,
                customSelectionColorHex = "#FEFEFE"
            )
        )
        val untouched = 0xFEFEFEL

        assertNotEquals(untouched, washedOut.fillArgb and 0xFFFFFFL)
    }

    @Test
    fun `a malformed custom colour falls back to the theme default`() {
        val malformed = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(customSelectionColorHex = "not-a-colour")
        )
        val default = ReaderSelectionVisualStyle.palette(ReaderAppearance())

        assertEquals(default, malformed)
    }

    @Test
    fun `css output is the form the injected stylesheet expects`() {
        val palette = ReaderSelectionVisualStyle.palette(ReaderAppearance())

        assertTrue(palette.fillCss.startsWith("rgba("), palette.fillCss)
        assertTrue(palette.strokeCss.startsWith("rgba("), palette.strokeCss)
    }

    /** sRGB relative luminance, mirroring the production constants. */
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((argb shr shift) and 0xFF) / 255.0
            return if (value <= 0.04045) value / 12.92
            else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** The fill as the reader actually sees it, composited over the page. */
    private fun compositedContrast(fillArgb: Long, backgroundHex: String): Double {
        val bg = backgroundHex.removePrefix("#").toLong(16)
        val a = alpha(fillArgb) / 255.0
        var visible = 0L
        for (shift in intArrayOf(16, 8, 0)) {
            val f = ((fillArgb shr shift) and 0xFF) / 255.0
            val b = ((bg shr shift) and 0xFF) / 255.0
            val v = (f * a + b * (1 - a)).coerceIn(0.0, 1.0)
            visible = visible or ((v * 255).roundToLong() shl shift)
        }
        val first = luminance(visible)
        val second = luminance(bg)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    @Test
    fun `a saturated mid-tone page still gets a visible selection`() {
        // Azure page, azure accent. Its relative luminance (0.34) falls under
        // the 0.38 "dark page" cut, so the old code mixed the accent towards
        // *white* — against a page this bright that never reached the
        // visibility floor no matter how far it went, and the selection came
        // out invisible. This is the "custom selection colour stops working
        // once a custom background is set" report.
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(
                customBackgroundHex = "#00A0FF",
                customSelectionColorHex = "#0060E0"
            )
        )

        assertTrue(
            compositedContrast(palette.fillArgb, "#00A0FF") >= 1.55,
            "selection is invisible on this page: ${palette.fillCss}"
        )
    }

    @Test
    fun `a custom accent keeps its hue instead of being washed to the pole`() {
        // Orange accent on a coral page. Both old failure modes at once: the
        // page classified as dark, so the accent was mixed towards white — 80%
        // of the way after eight fixed nudges — and arrived as a pale cream
        // with no orange left in it.
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(
                customBackgroundHex = "#FF8060",
                customSelectionColorHex = "#FF8000"
            )
        )
        val red = ((palette.fillArgb shr 16) and 0xFF).toInt()
        val blue = (palette.fillArgb and 0xFF).toInt()

        assertTrue(
            compositedContrast(palette.fillArgb, "#FF8060") >= 1.55,
            "selection is invisible on this page: ${palette.fillCss}"
        )
        assertTrue(
            red - blue > 60,
            "the chosen orange should still read as orange, got ${palette.fillCss}"
        )
    }

    @Test
    fun `an accent that already clears the floor is delivered untouched`() {
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(
                theme = ReaderThemeOption.LIGHT,
                customSelectionColorHex = "#FF3B30"
            )
        )

        assertEquals(
            0xFF3B30L,
            palette.fillArgb and 0xFFFFFFL,
            "a colour that is already visible must not be adjusted at all"
        )
    }

    @Test
    fun `every theme produces a stroke more opaque than its fill`() {
        ReaderThemeOption.entries.forEach { theme ->
            val palette = ReaderSelectionVisualStyle.palette(ReaderAppearance(theme = theme))
            assertTrue(
                alpha(palette.strokeArgb) > alpha(palette.fillArgb),
                "$theme: the outline is what makes the selection read as one shape"
            )
        }
    }
}
