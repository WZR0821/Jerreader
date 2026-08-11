package com.jerreader.unified.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JerreaderAppPaletteTest {

    @Test
    fun `every accent produces its own surfaces`() {
        // The whole point of 1.3.3: picking 琥珀 must not leave the same grey
        // page it had under 海蓝 with only the buttons repainted.
        val canvases = JerreaderAccentPalette.entries.map {
            JerreaderAppPalette.colors(it, dark = false).canvas
        }
        assertEquals(canvases.size, canvases.distinct().size)
    }

    @Test
    fun `light surfaces stay bright enough to read on`() {
        for (palette in JerreaderAccentPalette.entries) {
            val colors = JerreaderAppPalette.colors(palette, dark = false)
            for (surface in listOf(colors.canvas, colors.paper, colors.raisedPaper)) {
                val luminance = surface.red * 0.2126 + surface.green * 0.7152 + surface.blue * 0.0722
                assertTrue(
                    luminance > 0.78,
                    "$palette light surface too dark: $luminance"
                )
            }
        }
    }

    @Test
    fun `dark surfaces stay dark`() {
        for (palette in JerreaderAccentPalette.entries) {
            val colors = JerreaderAppPalette.colors(palette, dark = true)
            for (surface in listOf(colors.canvas, colors.paper, colors.raisedPaper)) {
                val luminance = surface.red * 0.2126 + surface.green * 0.7152 + surface.blue * 0.0722
                assertTrue(
                    luminance < 0.25,
                    "$palette dark surface too light: $luminance"
                )
            }
        }
    }

    @Test
    fun `paper sits above the canvas in light mode and below it in dark`() {
        for (palette in JerreaderAccentPalette.entries) {
            val light = JerreaderAppPalette.colors(palette, dark = false)
            assertTrue(
                light.paper.red > light.canvas.red,
                "$palette light paper must be lighter than its canvas"
            )
            val dark = JerreaderAppPalette.colors(palette, dark = true)
            assertTrue(
                dark.raisedPaper.red > dark.paper.red,
                "$palette raised paper must lift off the page in dark mode"
            )
        }
    }

    @Test
    fun `the tint is a wash rather than a repaint`() {
        // Surfaces must stay far from the accent itself, or covers and reader
        // content stop reading as paper.
        for (palette in JerreaderAccentPalette.entries) {
            for (dark in listOf(false, true)) {
                val colors = JerreaderAppPalette.colors(palette, dark)
                val accent = JerreaderAppPalette.accentRgb(palette, dark)
                val distance = abs(colors.paper.red - accent.red) +
                    abs(colors.paper.green - accent.green) +
                    abs(colors.paper.blue - accent.blue)
                assertTrue(distance > 0.6, "$palette dark=$dark paper too close to its accent")
            }
        }
    }

    @Test
    fun `fromId falls back to ocean`() {
        assertEquals(JerreaderAccentPalette.AMBER, JerreaderAccentPalette.fromId("amber"))
        assertEquals(JerreaderAccentPalette.OCEAN, JerreaderAccentPalette.fromId("nope"))
        for (palette in JerreaderAccentPalette.entries) {
            assertEquals(palette, JerreaderAccentPalette.fromId(palette.id))
        }
    }

    @Test
    fun `an appearance mode decides for itself except when it follows the system`() {
        assertFalse(JerreaderAppearanceMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(JerreaderAppearanceMode.DARK.isDark(systemIsDark = false))
        assertTrue(JerreaderAppearanceMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(JerreaderAppearanceMode.SYSTEM.isDark(systemIsDark = false))
    }

    @Test
    fun `an appearance mode round-trips through its stored id`() {
        for (mode in JerreaderAppearanceMode.entries) {
            assertEquals(mode, JerreaderAppearanceMode.fromId(mode.id))
        }
        // An unset preference, or one written by a build that offered more
        // modes, leaves the app following the phone rather than stuck light.
        assertEquals(JerreaderAppearanceMode.SYSTEM, JerreaderAppearanceMode.fromId(""))
        assertEquals(JerreaderAppearanceMode.SYSTEM, JerreaderAppearanceMode.fromId("sepia"))
    }
}
