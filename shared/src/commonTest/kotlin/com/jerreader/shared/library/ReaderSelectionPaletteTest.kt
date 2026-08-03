package com.jerreader.shared.library

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
}

private fun Long.rgbSum(): Long =
    ((this shr 16) and 0xFF) + ((this shr 8) and 0xFF) + (this and 0xFF)
