package com.jerreader.shared.library

import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue

class ReaderSelectionPaletteTest {
    @Test
    fun strokeIsDarkerThanFillOnALightTheme() {
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(theme = ReaderThemeOption.LIGHT)
        )
        assertTrue(palette.strokeArgb.rgbSum() < palette.fillArgb.rgbSum())
        assertTrue(palette.strokeCss.startsWith("rgba("))
    }

    @Test
    fun strokeIsLighterThanFillOnADarkTheme() {
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(theme = ReaderThemeOption.DARK)
        )
        assertTrue(palette.strokeArgb.rgbSum() > palette.fillArgb.rgbSum())
    }

    @Test
    fun eachThemeKeepsItsOwnHue() {
        val hues = ReaderThemeOption.entries.map {
            ReaderSelectionVisualStyle.palette(ReaderAppearance(theme = it)).fillArgb and 0xFFFFFF
        }
        assertTrue(hues.distinct().size == hues.size)
    }

    @Test
    fun aCustomSelectionColourWins() {
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(customSelectionColorHex = "#CC3366")
        )
        val red = (palette.fillArgb shr 16) and 0xFF
        val blue = palette.fillArgb and 0xFF
        assertTrue(red > blue)
    }

    @Test
    fun aSaturatedMidToneBackgroundStillGetsAVisibleSelection() {
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
    fun aCustomAccentKeepsItsHueInsteadOfBeingWashedToThePole() {
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
    fun anAccentThatAlreadyClearsTheFloorIsDeliveredUntouched() {
        val palette = ReaderSelectionVisualStyle.palette(
            ReaderAppearance(
                theme = ReaderThemeOption.LIGHT,
                customSelectionColorHex = "#FF3B30"
            )
        )
        assertTrue(
            palette.fillArgb and 0xFFFFFF == 0xFF3B30L,
            "an accent that already reads should not be adjusted, got ${palette.fillCss}"
        )
    }

    /** WCAG relative luminance of an 0xRRGGBB value. */
    private fun luminance(rgb: Long): Double {
        fun channel(value: Long): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((rgb shr 16) and 0xFF) +
            0.7152 * channel((rgb shr 8) and 0xFF) +
            0.0722 * channel(rgb and 0xFF)
    }

    /** The fill as the reader actually sees it, composited over the page. */
    private fun compositedContrast(fillArgb: Long, backgroundHex: String): Double {
        val bg = backgroundHex.removePrefix("#").toLong(16)
        val a = ((fillArgb shr 24) and 0xFF) / 255.0
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
}

private fun Long.rgbSum(): Long =
    ((this shr 16) and 0xFF) + ((this shr 8) and 0xFF) + (this and 0xFF)
